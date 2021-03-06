/*
 *    Copyright 2016 Roche NimbleGen Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.roche.sequencing.bioinformatics.common.mapping;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.roche.sequencing.bioinformatics.common.sequence.ISequence;
import com.roche.sequencing.bioinformatics.common.sequence.NucleotideCodeSequence;

/**
 * 
 * Allows reference objects to be tied to a given sequence. Query sequence can then be used to query the mapping to find the most similar sequence.
 * 
 * @param <O>
 *            reference objects
 */
public class SimpleMapper<O> {

	private final static int DEFAULT_COMPARISON_SEQUENCE_SIZE = 5;
	private final static int DEFAULT_REFERENCE_SPACING = 1;
	private final static int DEFAULT_QUERY_SPACING = 1;
	private final static int DEFAULT_BEST_CANDIDATE_LIMIT = 10;
	private final static int DEFAULT_MAX_REFERENCES_STORED_PER_SEQUENCE = 50;
	private final static double DEFAULT_MIN_RATIO_OF_HITS_TO_AVAILABLE_HITS = 0.5;
	private final int comparisonSequenceSize;
	private final int referenceSpacing;
	private final int querySpacing;
	private final Integer maxReferencesStoredPerSequence;
	private final HashSet<ISequence> sequencesToExclude;

	private final Map<ISequence, Set<O>> sequenceSliceToReferenceAddressMap;
	private final Map<O, Integer> referenceAddressesToSizeMap;

	/**
	 * Default Constructor
	 */
	public SimpleMapper() {
		this(DEFAULT_COMPARISON_SEQUENCE_SIZE, DEFAULT_REFERENCE_SPACING, DEFAULT_QUERY_SPACING, DEFAULT_MAX_REFERENCES_STORED_PER_SEQUENCE);
	}

	/**
	 * Constructor
	 * 
	 * @param comparisonSequenceSize
	 *            the size of the chunks that should be used for comparing
	 * @param maxReferenceDepth
	 *            the number of spaces to skip when building a library of chunks to compare against
	 * @param querySpacing
	 *            the number of spaces to skip when comparing chunks from a query sequence
	 * @param minHitThreshold
	 *            min number of hits required to return as a best candidate reference
	 */
	public SimpleMapper(int comparisonSequenceSize, int referenceSpacing, int querySpacing, Integer maxReferencesStoredPerSequence) {
		this.comparisonSequenceSize = comparisonSequenceSize;
		this.referenceSpacing = referenceSpacing;
		this.querySpacing = querySpacing;
		this.maxReferencesStoredPerSequence = maxReferencesStoredPerSequence;
		this.sequenceSliceToReferenceAddressMap = new ConcurrentHashMap<ISequence, Set<O>>();
		this.sequencesToExclude = new HashSet<ISequence>();
		this.referenceAddressesToSizeMap = new HashMap<>();
	}

	/**
	 * Add a reference sequence with its associated unique identifier/key/sequence address
	 * 
	 * @param referenceSequence
	 * @param sequenceAddress
	 */
	public void addReferenceSequence(ISequence referenceSequence, O sequenceAddress) {
		referenceAddressesToSizeMap.put(sequenceAddress, referenceSequence.size());
		if (referenceSequence.size() >= comparisonSequenceSize) {
			for (int subsequenceStartIndex = 0; subsequenceStartIndex < referenceSequence.size() - comparisonSequenceSize; subsequenceStartIndex += referenceSpacing) {
				addSliceToReferenceMap(referenceSequence.subSequence(subsequenceStartIndex, subsequenceStartIndex + comparisonSequenceSize - 1), sequenceAddress);
			}
		} else {
			throw new IllegalStateException(
					"comparison sequence size[" + comparisonSequenceSize + "] must be less than the size of all sequences -- the current sequence size is " + referenceSequence.size() + ".");
		}
	}

	/**
	 * Add a reference sequence with its associated unique identifier/key/sequence address
	 * 
	 * @param referenceSequence
	 * @param sequenceAddress
	 */

	public void removeReferenceSequenceByAddress(O sequenceAddress) {
		referenceAddressesToSizeMap.remove(sequenceAddress);
		for (Entry<ISequence, Set<O>> entry : sequenceSliceToReferenceAddressMap.entrySet()) {
			Set<O> set = entry.getValue();
			if (set.contains(sequenceAddress)) {
				if (set.size() == 1) {
					sequenceSliceToReferenceAddressMap.remove(entry.getKey());
				} else {
					set.remove(sequenceAddress);
					sequenceSliceToReferenceAddressMap.put(entry.getKey(), set);
				}
			}
		}
	}

	private void addSliceToReferenceMap(ISequence sequence, O sequenceAddress) {
		if (!(sequence instanceof NucleotideCodeSequence)) {
			sequence = new NucleotideCodeSequence(sequence);
		}
		if (!sequencesToExclude.contains(sequence)) {
			Set<O> sequenceAddresses = sequenceSliceToReferenceAddressMap.get(sequence);
			if (sequenceAddresses == null) {
				sequenceAddresses = new HashSet<O>();
				sequenceSliceToReferenceAddressMap.put(sequence, sequenceAddresses);
			}
			sequenceAddresses.add(sequenceAddress);
			if (maxReferencesStoredPerSequence != null && sequenceAddresses.size() > maxReferencesStoredPerSequence) {
				sequenceSliceToReferenceAddressMap.remove(sequence);
				sequencesToExclude.add(sequence);
			}

		}
	}

	public List<O> getBestCandidateReferences(ISequence querySequence, int limit) {
		return getBestCandidateReferences(querySequence, limit, DEFAULT_MIN_RATIO_OF_HITS_TO_AVAILABLE_HITS);
	}

	/**
	 * @param querySequence
	 * @return the set of unique identifiers/keys/sequence addresses that best map to the provided query sequence
	 */
	@SuppressWarnings("unchecked")
	public List<O> getBestCandidateReferences(ISequence querySequence, int limitOnNumberOfCandidatesToReturn, double minRatioOfHitsToAvailableHits) {
		double availableHitsBasedOnQuery = (double) (querySequence.size() - comparisonSequenceSize) / (double) querySpacing;
		double hitLimitBasedOnQueryMinRatio = minRatioOfHitsToAvailableHits * availableHitsBasedOnQuery;

		TallyMap<O> matchTallies = getReferenceTallyMap(querySequence);

		int lastAddeHitSize = 0;

		List<O> bestCandidates = new LinkedList<O>();
		if (matchTallies.getLargestCount() > 0) {
			entryLoop: for (Entry<O, Integer> entry : matchTallies.getObjectsSortedFromMostTalliesToLeast()) {
				O key = entry.getKey();
				int referenceSize = referenceAddressesToSizeMap.get(key);

				double availableHitsBasedOnReference = (double) (referenceSize - comparisonSequenceSize) / (double) referenceSpacing;
				double hitLimitBasedOnReferenceMinRatio = minRatioOfHitsToAvailableHits * availableHitsBasedOnReference;

				int hits = entry.getValue();
				boolean entryRejectedBecauseHitsAreBelowMinRatio = (hits < hitLimitBasedOnQueryMinRatio) && (hits < hitLimitBasedOnReferenceMinRatio);
				boolean entryRejectedBecauseCandidateLimitIsReachedAndIsNotTiedWithACurrentCandidate = (bestCandidates.size() > limitOnNumberOfCandidatesToReturn) && (hits < lastAddeHitSize);
				if (entryRejectedBecauseHitsAreBelowMinRatio || entryRejectedBecauseCandidateLimitIsReachedAndIsNotTiedWithACurrentCandidate) {
					// since we are walking through this from best to worst we can skip the rest of the entries once
					// we get one failure
					break entryLoop;
				} else {
					bestCandidates.add(entry.getKey());
					lastAddeHitSize = hits;
				}
			}
		} else {
			bestCandidates = (List<O>) Collections.EMPTY_LIST;
		}

		return bestCandidates;
	}

	/**
	 * @param querySequence
	 * @return the set of unique identifiers/keys/sequence addresses that best map to the provided query sequence
	 */
	public List<O> getBestCandidateReferences(ISequence querySequence) {
		return getBestCandidateReferences(querySequence, DEFAULT_BEST_CANDIDATE_LIMIT, DEFAULT_MIN_RATIO_OF_HITS_TO_AVAILABLE_HITS);
	}

	public int getOptimalScore(ISequence querySequence) {
		return querySequence.size() - comparisonSequenceSize;
	}

	/**
	 * @param querySequence
	 * @return the tallyMap associated with hits for this query sequence
	 */
	public TallyMap<O> getReferenceTallyMap(ISequence querySequence) {
		if (!(querySequence instanceof NucleotideCodeSequence)) {
			querySequence = new NucleotideCodeSequence(querySequence);
		}

		TallyMap<O> matchTallies = new TallyMap<O>();
		for (int i = 0; i < querySequence.size() - comparisonSequenceSize; i += querySpacing) {
			ISequence querySequenceSlice = querySequence.subSequence(i, i + comparisonSequenceSize - 1);
			Set<O> addresses = sequenceSliceToReferenceAddressMap.get(querySequenceSlice);
			matchTallies.addAll(addresses);
		}
		return matchTallies;
	}

}
