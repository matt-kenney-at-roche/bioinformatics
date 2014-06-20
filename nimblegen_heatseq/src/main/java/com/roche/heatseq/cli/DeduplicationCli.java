/**
 *   Copyright 2013 Roche NimbleGen
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.roche.heatseq.cli;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileReader.ValidationStringency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.roche.heatseq.objects.ApplicationSettings;
import com.roche.heatseq.process.FastqAndBamFileMerger;
import com.roche.heatseq.process.PrimerReadExtensionAndPcrDuplicateIdentification;
import com.roche.heatseq.qualityreport.LoggingUtil;
import com.roche.heatseq.utils.BamFileUtil;
import com.roche.heatseq.utils.ProbeFileUtil;
import com.roche.sequencing.bioinformatics.common.alignment.IAlignmentScorer;
import com.roche.sequencing.bioinformatics.common.alignment.SimpleAlignmentScorer;
import com.roche.sequencing.bioinformatics.common.commandline.CommandLineOption;
import com.roche.sequencing.bioinformatics.common.commandline.CommandLineOptionsGroup;
import com.roche.sequencing.bioinformatics.common.commandline.ParsedCommandLine;
import com.roche.sequencing.bioinformatics.common.utils.DateUtil;
import com.roche.sequencing.bioinformatics.common.utils.FileUtil;

public class DeduplicationCli {
	private final static Logger logger = LoggerFactory.getLogger(DeduplicationCli.class);

	public final static int DEFAULT_EXTENSION_UID_LENGTH = 10;
	public final static int DEFAULT_LIGATION_UID_LENGTH = 0;
	public final static String BAM_EXTENSION = ".bam";

	public final static CommandLineOption USAGE_OPTION = new CommandLineOption("Print Usage", "usage", 'h', "Print Usage.", false, true);
	public final static CommandLineOption FASTQ_ONE_OPTION = new CommandLineOption("FastQ One File", "r1", null, "Path to first input fastq file.", true, false);
	public final static CommandLineOption FASTQ_TWO_OPTION = new CommandLineOption("FastQ Two File", "r2", null, "Path to second input fastq file.", true, false);
	public final static CommandLineOption INPUT_BAM_OPTION = new CommandLineOption("Input BAM or SAM File Path", "inputBam", null, "Path to input BAM or SAM file containing the aligned reads.", true,
			false);
	public final static CommandLineOption PROBE_OPTION = new CommandLineOption("Probe Information File", "probe", null, "NimbleGen probe file.", true, false);
	public final static CommandLineOption OUTPUT_DIR_OPTION = new CommandLineOption("Output Directory", "outputDir", null, "Location to store resultant files.", false, false);
	public final static CommandLineOption OUTPUT_FILE_PREFIX_OPTION = new CommandLineOption("Output File Prefix", "outputPrefix", null, "Text to put at beginning of output file names.", false, false);
	public final static CommandLineOption TMP_DIR_OPTION = new CommandLineOption("Temporary Directory", "tmpDir", null, "Location to store temporary files.", false, false);
	public final static CommandLineOption NUM_PROCESSORS_OPTION = new CommandLineOption("Number of Processors", "numProcessors", null,
			"The number of threads to run in parallel.  If not specified this will default to the number of cores available on the machine.", false, false);
	public final static CommandLineOption OUTPUT_BAM_FILE_NAME_OPTION = new CommandLineOption("Output Bam File Name", "outputBamFileName", 'o', "Name for output bam file.", true, false);
	private final static CommandLineOption MATCH_SCORE_OPTION = new CommandLineOption("Match Score", "matchScore", null,
			"The score given to matching nucleotides when extending alignments to the primers. (Default: " + SimpleAlignmentScorer.DEFAULT_MATCH_SCORE + ")", false, false);
	private final static CommandLineOption MISMATCH_PENALTY_OPTION = new CommandLineOption("Mismatch Penalty", "mismatchPenalty", null,
			"The penalty subtracted for mismatched nucleotides when extending alignments to the primers. (Default: " + SimpleAlignmentScorer.DEFAULT_MISMATCH_PENALTY + ")", false, false);
	private final static CommandLineOption GAP_OPEN_PENALTY_OPTION = new CommandLineOption("Gap Open Penalty", "gapOpenPenalty", null,
			"The penalty for opening a gap when extending alignments to the primers. (Default: " + SimpleAlignmentScorer.DEFAULT_GAP_OPEN_PENALTY + ")", false, false);
	private final static CommandLineOption GAP_EXTEND_PENALTY_OPTION = new CommandLineOption("Gap Extend Penalty", "gapExtendPenalty", null,
			"The penalty for extending a gap when extending alignments to the primers. (Default: " + SimpleAlignmentScorer.DEFAULT_GAP_EXTEND_PENALTY + ")", false, false);
	public final static CommandLineOption LENIENT_VALIDATION_STRINGENCY_OPTION = new CommandLineOption("Lenient Validation Stringency", "lenientValidation", null,
			"Use a lenient validation stringency for all SAM files read by this program.", false, true);
	private final static CommandLineOption MARK_DUPLICATES_OPTION = new CommandLineOption("Mark Duplicates", "markDuplicates", null, "Mark duplicate reads in the bam file instead of removing them.",
			false, true);
	private final static CommandLineOption KEEP_DUPLICATES_OPTION = new CommandLineOption("Keep Duplicates", "keepDuplicates", null, "Keep duplicate reads in the bam file instead of removing them. ",
			false, true);
	public final static CommandLineOption MERGE_PAIRS_OPTION = new CommandLineOption("Merge Pairs", "mergePairs", null, "Merge pairs using the highest quality base reads from each read.", false, true);
	private final static CommandLineOption NOT_TRIMMED_TO_WITHIN_CAPTURE_TARGET_OPTION = new CommandLineOption("Reads Are Not Trimmed To Within Capture Target", "readsNotTrimmedWithinCaptureTarget",
			null, "The reads have not been trimmed to an area within the capture target.", false, true);

	// Note: these variables are for debugging purposes
	// saveTemporaryFiles default is false
	private final static boolean saveTemporaryFiles = false;
	// allowVaraibleLengthUids default is false
	private final static boolean allowVariableLengthUids = false;
	// useStrictReadToProbeMatching default is false
	private final static boolean useStrictReadToProbeMatching = false;
	// shouldOutputQualityReports default is true
	private final static boolean shouldOutputQualityReports = true;

	static void identifyDuplicates(ParsedCommandLine parsedCommandLine, String commandLineSignature, String applicationName, String applicationVersion) {
		String outputDirectoryString = parsedCommandLine.getOptionsValue(OUTPUT_DIR_OPTION);
		File outputDirectory = null;
		if (outputDirectoryString != null) {
			outputDirectory = new File(outputDirectoryString);
			if (!outputDirectory.exists()) {
				try {
					FileUtil.createDirectory(outputDirectory);
				} catch (IOException e) {
					throw new IllegalStateException("Could not create provided output directory[" + outputDirectory.getAbsolutePath() + "].", e);
				}
			}
			if (!outputDirectory.isDirectory()) {
				throw new IllegalStateException("Provided output directory[" + outputDirectory.getAbsolutePath() + "] is not valid.");
			}
		} else {
			// current working directory
			outputDirectory = new File(".");
		}

		String outputFilePrefix = parsedCommandLine.getOptionsValue(OUTPUT_FILE_PREFIX_OPTION);
		if (outputFilePrefix == null) {
			outputFilePrefix = "";
		}

		String logFileName = outputFilePrefix;
		if (!outputFilePrefix.isEmpty()) {
			logFileName = outputFilePrefix + "_";
		}
		logFileName = logFileName + applicationName + DateUtil.getCurrentDateINYYYY_MM_DD_HH_MM_SS() + ".log";
		logFileName = logFileName.replaceAll(" ", "_");
		logFileName = logFileName.replaceAll("/", "_");
		logFileName = logFileName.replaceAll(":", "-");
		File logFile = new File(outputDirectory, logFileName);
		try {
			LoggingUtil.setLogFile(HsqUtilsCli.FILE_LOGGER_NAME, logFile);
		} catch (IOException e2) {
			throw new IllegalStateException("Unable to create log file at " + logFile.getAbsolutePath() + ".", e2);
		}
		logger.info(applicationName + " version:" + applicationVersion);
		logger.info("command line signature: " + commandLineSignature);
		String tempDirectoryString = parsedCommandLine.getOptionsValue(TMP_DIR_OPTION);
		File tempDirectory = null;
		if (tempDirectoryString != null) {
			tempDirectory = new File(tempDirectoryString);
			try {
				FileUtil.createDirectory(tempDirectory);
			} catch (IOException e) {
				throw new IllegalStateException("Unable to create provided temporary directory[" + tempDirectory.getAbsolutePath() + "].");
			}
			if (!tempDirectory.exists() || !tempDirectory.isDirectory()) {
				throw new IllegalStateException("Unable to find provided temporary directory[" + tempDirectory.getAbsolutePath() + "].");
			}
		} else {
			// default temp directory
			tempDirectory = FileUtil.getSystemSpecificTempDirectory();
		}

		File fastQ1File = new File(parsedCommandLine.getOptionsValue(FASTQ_ONE_OPTION));

		if (!fastQ1File.exists()) {
			throw new IllegalStateException("Unable to find provided FASTQ1 file[" + fastQ1File.getAbsolutePath() + "].");
		}

		File fastQ2File = new File(parsedCommandLine.getOptionsValue(FASTQ_TWO_OPTION));

		if (!fastQ2File.exists()) {
			throw new IllegalStateException("Unable to find provided FASTQ2 file[" + fastQ2File.getAbsolutePath() + "].");
		}

		File probeFile = new File(parsedCommandLine.getOptionsValue(PROBE_OPTION));

		if (!probeFile.exists()) {
			throw new IllegalStateException("Unable to find provided PROBE file[" + probeFile.getAbsolutePath() + "].");
		}

		int numProcessors = Runtime.getRuntime().availableProcessors();
		if (parsedCommandLine.isOptionPresent(NUM_PROCESSORS_OPTION)) {
			try {
				numProcessors = Integer.parseInt(parsedCommandLine.getOptionsValue(NUM_PROCESSORS_OPTION));

			} catch (NumberFormatException ex) {
				throw new IllegalStateException("Value specified for number of processors is not an integer[" + parsedCommandLine.getOptionsValue(NUM_PROCESSORS_OPTION) + "].");
			}
		}

		Integer extensionUidLength;
		try {
			extensionUidLength = ProbeFileUtil.extractExtensionUidLength(probeFile);
			if (extensionUidLength == null) {
				extensionUidLength = DEFAULT_EXTENSION_UID_LENGTH;
			}
		} catch (FileNotFoundException e1) {
			throw new IllegalStateException(e1);
		}

		Integer ligationUidLength;
		try {
			ligationUidLength = ProbeFileUtil.extractLigationUidLength(probeFile);
			if (ligationUidLength == null) {
				ligationUidLength = DEFAULT_LIGATION_UID_LENGTH;
			}
		} catch (FileNotFoundException e1) {
			throw new IllegalStateException(e1);
		}

		String outputBamFileName = parsedCommandLine.getOptionsValue(OUTPUT_BAM_FILE_NAME_OPTION);
		if (!outputBamFileName.endsWith(BAM_EXTENSION)) {
			outputBamFileName += BAM_EXTENSION;
		}

		// Set up our alignment scorer
		double matchScore = SimpleAlignmentScorer.DEFAULT_MATCH_SCORE;
		double mismatchPenalty = SimpleAlignmentScorer.DEFAULT_MISMATCH_PENALTY;
		double gapOpenPenalty = SimpleAlignmentScorer.DEFAULT_GAP_OPEN_PENALTY;
		double gapExtendPenalty = SimpleAlignmentScorer.DEFAULT_GAP_EXTEND_PENALTY;

		if (parsedCommandLine.isOptionPresent(MATCH_SCORE_OPTION)) {
			try {
				matchScore = Integer.parseInt(parsedCommandLine.getOptionsValue(MATCH_SCORE_OPTION));
				if (matchScore < 0) {
					throw new IllegalStateException("Value specified for match score must be >= 0 [" + parsedCommandLine.getOptionsValue(MATCH_SCORE_OPTION) + "].");
				}
			} catch (NumberFormatException ex) {
				throw new IllegalStateException("Value specified for match score is not an integer[" + parsedCommandLine.getOptionsValue(MATCH_SCORE_OPTION) + "].");
			}
		}
		if (parsedCommandLine.isOptionPresent(MISMATCH_PENALTY_OPTION)) {
			try {
				mismatchPenalty = -Integer.parseInt(parsedCommandLine.getOptionsValue(MISMATCH_PENALTY_OPTION));
				if (mismatchPenalty > 0) {
					throw new IllegalStateException("Value specified for mismatch penalty must be >= 0 [" + parsedCommandLine.getOptionsValue(MISMATCH_PENALTY_OPTION) + "].");
				}
			} catch (NumberFormatException ex) {
				throw new IllegalStateException("Value specified for mismatch penalty is not an integer[" + parsedCommandLine.getOptionsValue(MISMATCH_PENALTY_OPTION) + "].");
			}
		}
		if (parsedCommandLine.isOptionPresent(GAP_OPEN_PENALTY_OPTION)) {
			try {
				gapOpenPenalty = -Integer.parseInt(parsedCommandLine.getOptionsValue(GAP_OPEN_PENALTY_OPTION));
				if (gapOpenPenalty > 0) {
					throw new IllegalStateException("Value specified for gap open penalty must be >= 0 [" + parsedCommandLine.getOptionsValue(GAP_OPEN_PENALTY_OPTION) + "].");
				}
			} catch (NumberFormatException ex) {
				throw new IllegalStateException("Value specified for gap open penalty is not an integer[" + parsedCommandLine.getOptionsValue(GAP_OPEN_PENALTY_OPTION) + "].");
			}
		}
		if (parsedCommandLine.isOptionPresent(GAP_EXTEND_PENALTY_OPTION)) {
			try {
				gapExtendPenalty = -Integer.parseInt(parsedCommandLine.getOptionsValue(GAP_EXTEND_PENALTY_OPTION));
				if (gapExtendPenalty > 0) {
					throw new IllegalStateException("Value specified for gap extend penalty must be >= 0 [" + parsedCommandLine.getOptionsValue(GAP_EXTEND_PENALTY_OPTION) + "].");
				}
			} catch (NumberFormatException ex) {
				throw new IllegalStateException("Value specified for gap extend penalty not an integer[" + parsedCommandLine.getOptionsValue(GAP_EXTEND_PENALTY_OPTION) + "].");
			}
		}

		boolean useLenientValidation = parsedCommandLine.isOptionPresent(LENIENT_VALIDATION_STRINGENCY_OPTION);
		if (useLenientValidation) {
			SAMFileReader.setDefaultValidationStringency(ValidationStringency.LENIENT);
		}

		boolean markDuplicates = parsedCommandLine.isOptionPresent(MARK_DUPLICATES_OPTION);
		boolean keepDuplicates = parsedCommandLine.isOptionPresent(KEEP_DUPLICATES_OPTION);

		if (markDuplicates && keepDuplicates) {
			throw new IllegalStateException(MARK_DUPLICATES_OPTION.getLongFormOption() + " and " + KEEP_DUPLICATES_OPTION.getLongFormOption() + " cannot be used in the same run.");
		}

		boolean mergePairs = parsedCommandLine.isOptionPresent(MERGE_PAIRS_OPTION);

		boolean notTrimmedToWithinCaptureTarget = parsedCommandLine.isOptionPresent(NOT_TRIMMED_TO_WITHIN_CAPTURE_TARGET_OPTION);

		IAlignmentScorer alignmentScorer = new SimpleAlignmentScorer(matchScore, mismatchPenalty, gapExtendPenalty, gapOpenPenalty, false);

		try {
			String bamFileString = parsedCommandLine.getOptionsValue(INPUT_BAM_OPTION);
			File bamFile = new File(bamFileString);

			if (!bamFile.exists()) {
				throw new IllegalStateException("Unable to find provided BAM file[" + bamFile.getAbsolutePath() + "].");
			}

			Path tempOutputDirectoryPath = Files.createTempDirectory(tempDirectory.toPath(), "nimblegen_");
			final File tempOutputDirectory = tempOutputDirectoryPath.toFile();
			// Delete our temporary directory when we shut down the JVM if the user hasn't asked us to keep it
			if (!saveTemporaryFiles) {
				Runtime.getRuntime().addShutdownHook(new Thread() {
					@Override
					public void run() {
						try {
							FileUtil.deleteDirectory(tempOutputDirectory);
						} catch (IOException e) {
							outputToConsole("Couldn't delete temp directory [" + tempOutputDirectory.getAbsolutePath() + "]:" + e.getMessage());
						}
					}
				});
			}

			// Try to locate or create an index file for the input bam file
			File bamIndexFile = null;

			try (SAMFileReader samReader = new SAMFileReader(bamFile)) {
				boolean isSamFormat = !samReader.isBinary();

				if (!isSamFormat) {

					// Look for the index in the same location as the file but with a .bai extension instead of a .bam extension
					File tempBamIndexfile = new File(FileUtil.getFileNameWithoutExtension(bamFileString) + ".bai");
					if (tempBamIndexfile.exists()) {
						bamIndexFile = tempBamIndexfile;
						outputToConsole("Using the BAM Index File located at [" + bamIndexFile + "].");
					}

					// Try looking for a .bai file in the same location as the bam file
					if (bamIndexFile == null) {
						// Try looking for a .bam.bai file in the same location as the bam file
						tempBamIndexfile = new File(bamFileString + ".bai");
						if (tempBamIndexfile.exists()) {
							bamIndexFile = tempBamIndexfile;
							outputToConsole("Using the BAM Index File located at [" + bamIndexFile + "].");
						}
					}

					// We couldn't find an index file, create one in our temp directory
					if ((bamIndexFile == null) || !bamIndexFile.exists()) {
						// a bam index file was not provided so create one in the default location
						bamIndexFile = File.createTempFile("bam_index_", ".bai", tempOutputDirectory);
						outputToConsole("A BAM Index File was not found in the default location so creating bam index file at:" + bamIndexFile);
						try {

							BamFileUtil.createIndex(samReader, bamIndexFile);
						} catch (Exception e) {
							throw new IllegalStateException("Could not find or create bam index file at [" + bamIndexFile.getAbsolutePath() + "].", e);
						}
					}
				}
			}

			sortMergeFilterAndExtendReads(applicationName, applicationVersion, probeFile, bamFile, bamIndexFile, fastQ1File, fastQ2File, outputDirectory, outputBamFileName, outputFilePrefix,
					tempOutputDirectory, shouldOutputQualityReports, commandLineSignature, numProcessors, extensionUidLength, ligationUidLength, allowVariableLengthUids, alignmentScorer,
					notTrimmedToWithinCaptureTarget, markDuplicates, keepDuplicates, mergePairs, useStrictReadToProbeMatching);

		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}

	}

	public static void sortMergeFilterAndExtendReads(String applicationName, String applicationVersion, File probeFile, File bamFile, File bamIndexFile, File fastQ1WithUidsFile, File fastQ2File,
			File outputDirectory, String outputBamFileName, String outputFilePrefix, File tempOutputDirectory, boolean shouldOutputQualityReports, String commandLineSignature, int numProcessors,
			int extensionUidLength, int ligationUidLength, boolean allowVariableLengthUids, IAlignmentScorer alignmentScorer, boolean notTrimmedToWithinCaptureTarget, boolean markDuplicates,
			boolean keepDuplicates, boolean mergePairs, boolean useStrictReadToProbeMatching) {
		try {

			final File mergedBamFileSortedByCoordinates = File.createTempFile("merged_bam_sorted_by_coordinates_", ".bam", tempOutputDirectory);
			final File indexFileForMergedBamFileSortedByCoordinates = File.createTempFile("index_of_merged_bam_sorted_by_coordinates_", ".bamindex", tempOutputDirectory);

			long totalTimeStart = System.currentTimeMillis();

			FastqAndBamFileMerger.createMergedFastqAndBamFileFromUnsortedFiles(bamFile, bamIndexFile, fastQ1WithUidsFile, fastQ2File, mergedBamFileSortedByCoordinates);
			long timeAfterMergeUnsorted = System.currentTimeMillis();
			logger.debug("done merging bam and fastqfiles ... result[" + mergedBamFileSortedByCoordinates.getAbsolutePath() + "] in " + (timeAfterMergeUnsorted - totalTimeStart) + "ms.");

			// Build bam index
			SAMFileReader samReader = new SAMFileReader(mergedBamFileSortedByCoordinates);

			BamFileUtil.createIndexOnCoordinateSortedBamFile(samReader, indexFileForMergedBamFileSortedByCoordinates);
			long timeAfterBuildBamIndex = System.currentTimeMillis();
			logger.debug("done creating index for merged and sorted bam file ... result[" + indexFileForMergedBamFileSortedByCoordinates.getAbsolutePath() + "] in "
					+ DateUtil.convertMillisecondsToHHMMSS(timeAfterBuildBamIndex - timeAfterMergeUnsorted));
			samReader.close();

			ApplicationSettings applicationSettings = new ApplicationSettings(probeFile, mergedBamFileSortedByCoordinates, indexFileForMergedBamFileSortedByCoordinates, fastQ1WithUidsFile,
					fastQ2File, outputDirectory, outputBamFileName, outputFilePrefix, bamFile.getName(), shouldOutputQualityReports, commandLineSignature, applicationName, applicationVersion,
					numProcessors, allowVariableLengthUids, alignmentScorer, notTrimmedToWithinCaptureTarget, extensionUidLength, ligationUidLength, markDuplicates, keepDuplicates, mergePairs,
					useStrictReadToProbeMatching);

			PrimerReadExtensionAndPcrDuplicateIdentification.filterBamEntriesByUidAndExtendReadsToPrimers(applicationSettings);

			long totalTimeStop = System.currentTimeMillis();
			logger.debug("done - Total time: (" + DateUtil.convertMillisecondsToHHMMSS(totalTimeStop - totalTimeStart) + ")");
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	private static void outputToConsole(String output) {
		System.out.println(output);
	}

	public static CommandLineOptionsGroup getCommandLineOptionsGroup() {
		CommandLineOptionsGroup group = new CommandLineOptionsGroup();

		group.addOption(USAGE_OPTION);
		group.addOption(FASTQ_ONE_OPTION);
		group.addOption(FASTQ_TWO_OPTION);
		group.addOption(PROBE_OPTION);
		group.addOption(INPUT_BAM_OPTION);
		group.addOption(OUTPUT_DIR_OPTION);
		group.addOption(OUTPUT_BAM_FILE_NAME_OPTION);
		group.addOption(OUTPUT_FILE_PREFIX_OPTION);
		group.addOption(TMP_DIR_OPTION);
		group.addOption(NUM_PROCESSORS_OPTION);
		group.addOption(MATCH_SCORE_OPTION);
		group.addOption(MISMATCH_PENALTY_OPTION);
		group.addOption(GAP_OPEN_PENALTY_OPTION);
		group.addOption(GAP_EXTEND_PENALTY_OPTION);
		group.addOption(LENIENT_VALIDATION_STRINGENCY_OPTION);
		group.addOption(MARK_DUPLICATES_OPTION);
		group.addOption(KEEP_DUPLICATES_OPTION);
		group.addOption(MERGE_PAIRS_OPTION);
		group.addOption(NOT_TRIMMED_TO_WITHIN_CAPTURE_TARGET_OPTION);
		return group;
	}
}
