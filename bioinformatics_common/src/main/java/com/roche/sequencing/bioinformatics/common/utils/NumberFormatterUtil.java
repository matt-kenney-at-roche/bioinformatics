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
package com.roche.sequencing.bioinformatics.common.utils;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NumberFormatterUtil {

	private NumberFormatterUtil() {
		throw new AssertionError();
	}

	/**
	 * Formats a double as a string with a default number of significant digits depending on the argument's value.
	 * 
	 * @param someDouble
	 *            double to render
	 * @param includeThousandsSeparator
	 *            Specify if the comma should be included for numbers like 10,234.56
	 * @return such a string
	 */
	
	public static String formatDouble(Double someDouble, boolean includeThousandsSeparator) {

		int numberOfDigitsPastTheDecimal = 4;

		// Reduce the number of digits depending on the value of the double
		if (Math.abs(someDouble) >= 100.0) {
			numberOfDigitsPastTheDecimal = 3;
		}
		if (Math.abs(someDouble) >= 1000.0) {
			numberOfDigitsPastTheDecimal = 2;
		}

		return formatDouble(someDouble, numberOfDigitsPastTheDecimal, includeThousandsSeparator);
	}

	/**
	 * Formats a double as a string with the specified number of fractional digits.
	 * 
	 * @param someDouble
	 *            double to render
	 * @param fractionCount
	 *            Number of Fraction Digits
	 * @param includeThousandsSeparator
	 *            Specify if the comma should be included for numbers like 10,234.56
	 * 
	 * @return such a string
	 */
	private static String formatDouble(Double someDouble, int fractionCount, boolean includeThousandsSeparator) {
		NumberFormat numberFormat = NumberFormat.getInstance();
		numberFormat.setMinimumFractionDigits(fractionCount);
		numberFormat.setMaximumFractionDigits(fractionCount);
		numberFormat.setGroupingUsed(includeThousandsSeparator);
		String result = numberFormat.format(someDouble);
		return result;
	}

	/**
	 * Formats a double as a string with the specified number of fractional digits. Includes the thousands separator.
	 * 
	 * @param someDouble
	 *            double to render
	 * @param fractionCount
	 *            Number of Fraction Digits
	 * @return such a string
	 */
	
	public static String formatDouble(Double someDouble, int fractionCount) {
		return formatDouble(someDouble, fractionCount, true);
	}

	public static String addCommas(int value) {
		return NumberFormat.getNumberInstance(Locale.US).format(value);
	}

	/**
	 * Return a list of strings were adjacent values are clumped together. So 1,2,3,5,6,7,8,10 would be expressed as 1-3, 5-8, 10
	 * 
	 * @param numbers
	 * @return
	 */
	public static String[] summarizeNumbersAsString(int[] numbers) {
		// filter out duplicates
		Set<Integer> set = new HashSet<Integer>();
		for (int number : numbers) {
			set.add(number);
		}
		List<Integer> sortedNumbers = new ArrayList<Integer>(set);
		Collections.sort(sortedNumbers);

		List<String> summarizedStrings = new ArrayList<String>();

		Integer currentRunMin = null;
		Integer lastNumber = null;
		for (int number : sortedNumbers) {
			if (currentRunMin == null) {
				currentRunMin = number;
			} else if (number > (int) (lastNumber + 1)) {
				if (currentRunMin.equals(lastNumber)) {
					summarizedStrings.add(wrapNegativeNumbers(currentRunMin));
				} else {
					summarizedStrings.add(wrapNegativeNumbers(currentRunMin) + "-" + wrapNegativeNumbers(lastNumber));
				}
				currentRunMin = number;
			}
			lastNumber = number;
		}

		if (currentRunMin == null || currentRunMin.equals(lastNumber)) {
			summarizedStrings.add(wrapNegativeNumbers(lastNumber));
		} else {
			summarizedStrings.add(wrapNegativeNumbers(currentRunMin) + "-" + wrapNegativeNumbers(lastNumber));
		}

		return summarizedStrings.toArray(new String[0]);
	}

	private static String wrapNegativeNumbers(int number) {
		String valueAsString = "" + number;
		if (number < 0) {
			valueAsString = "(" + number + ")";
		}
		return valueAsString;
	}

	public static void main(String[] args) {
		System.out.println(ArraysUtil.toString(summarizeNumbersAsString(new int[] { 34, 35, 132, 370, 521, 1343, 1440, 1834, 2159, 2268, 4352 }), ", "));
	}
}
