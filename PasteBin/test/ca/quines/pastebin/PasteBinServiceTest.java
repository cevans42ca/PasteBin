package ca.quines.pastebin;

// This file is part of the "PasteBin" project.

// The "PasteBin" project is free software: you can redistribute it and/or modify it under the terms of the GNU
// General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// The "PasteBin" project is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
// even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// for more details.

// You should have received a copy of the GNU General Public License along with this project; if not, write to the
// Free Software Foundation, 59 Temple Place - Suite 330, Boston, MA, 02111-1307, USA.

// Copyright (C) 2022 Christopher Evans

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PasteBinServiceTest {

	private boolean NO_SAVE_ON_EXIT = false;

	private String[] VARIABLES = { "foo", "bar", "baz", "qux", "corge", "grault", "garply", "waldo",
			"fred", "plugh", "xyzzy", "thud" };

	/**
	 * We are strictly controlling the output, so we can get away with using a regex instead of bringing in
	 * more libraries just for testing.
	 * 
	 * We're matching the following.
	 * {@code <td id='text339658f0-169e-49ab-b862-fcde37100cef' class='top'>foo</td></code>} 
	 * 
	 * We want the value of the "id" attribute.  We're looking for "id=", a single quote, some text that isn't
	 * a single quote, the ending single quote, some text that isn't a right angle bracket, and a right angle
	 * bracket.  After this, we'll add what we're looking for (in this example, the text "foo") and a left
	 * angle bracket.
	 */
	private static final String ID_REGEX = "id='text([^']*)'[^>]*>";

	private void validateCleanService(String cleanResponse) {
		assertTrue(cleanResponse.contains("<title>PasteBin</title>"));
		assertTrue(cleanResponse.contains("<script>"));
		assertTrue(cleanResponse.contains("submitForm"));
		assertTrue(cleanResponse.contains("textarea"));
		assertFalse(cleanResponse.contains("foo"));
	}

	@Test
	void testNonExistantConfigFile() throws IOException {
		// Set up the temporary config file as a non-existant file.  We'll use the default settings.
		Path tempPath = Files.createTempFile("pasteBin", ".config");
		File tempFile = tempPath.toFile();
		tempFile.deleteOnExit();

		// Create the service and make sure it's clean.
		PasteBinService pasteBinService = new PasteBinService(tempFile, NO_SAVE_ON_EXIT);
		String cleanResponse = pasteBinService.rootHandler("/");
		validateCleanService(cleanResponse);
	}

	/**
	 * A functional test to demonstrate that if we load a config file that says to keep only 5 main entries,
	 * we only retain 5 entries.
	 * 
	 * @throws IOException
	 */
	@Test
	void testLoadConfigFileWith5Entries() throws IOException {
		// Set up a temporary config file and put config values in it.
		Path tempPath = Files.createTempFile("pasteBin", ".config");
		File tempFile = tempPath.toFile();

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
			bw.write(PasteBinService.CONFIG_MAX_MAIN_ENTRIES + "=" + 5);
			bw.newLine();
			bw.write(PasteBinService.CONFIG_MAX_KEEP_DELETED_DAYS + "=" + 60);
			bw.newLine();
		}

		tempFile.deleteOnExit();

		// Create the service and make sure it's clean.
		PasteBinService pasteBinService = new PasteBinService(tempFile, NO_SAVE_ON_EXIT);
		String cleanResponse = pasteBinService.rootHandler("/");
		validateCleanService(cleanResponse);

		int max = 4;

		Map<String, List<String>> queryMap;
		for (int i=0; i<=max; i++) {
			// Paste the current variable.
			queryMap = new HashMap<>();
			queryMap.put("text", List.of(VARIABLES[i]));
			String pasteResponse = pasteBinService.pasteHandler(queryMap);

			// Check the first one hasn't been removed yet.
			assertTrue(pasteResponse.contains(VARIABLES[0]));

			// Check this one is present.
			assertTrue(pasteResponse.contains(VARIABLES[i]));

			// Check the next one is not present.
			assertFalse(pasteResponse.contains(VARIABLES[i+1]));
		}

		queryMap = new HashMap<>();
		queryMap.put("text", List.of(VARIABLES[max+1]));
		String pasteResponse = pasteBinService.pasteHandler(queryMap);

		// Check the first one has been removed.
		assertFalse(pasteResponse.contains(VARIABLES[0]));

		// Check the last one is present.
		assertTrue(pasteResponse.contains(VARIABLES[max+1]));
	}

	/**
	 * This one is a functional test, checking broad strokes and how all the functions fit together in
	 * a normal workflow.
	 * 
	 * @throws IOException
	 */
	@Test
	void testOverview() throws IOException {
		// Set up the temporary config file as a non-existant file.  We'll use the default settings.
		Path tempPath = Files.createTempFile("pasteBin", ".config");
		File tempFile = tempPath.toFile();
		tempFile.deleteOnExit();

		// Create the service and make sure it's clean.
		PasteBinService pasteBinService = new PasteBinService(tempFile, NO_SAVE_ON_EXIT);
		String cleanResponse = pasteBinService.rootHandler("/");
		assertFalse(cleanResponse.contains("foo"));

		// Paste some text and confirm it's in the output.
		Map<String, List<String>> queryMap = new HashMap<>();
		queryMap.put("text", List.of("foo"));
		String changedResponse = pasteBinService.pasteHandler(queryMap);
		assertTrue(changedResponse.contains("foo"));

		// Confirm the root/default page is returning the text.
		String rootResponse = pasteBinService.rootHandler("/");
		assertTrue(rootResponse.contains("foo"));

		/**
		 * Extract the ID from the response.
		 * @see #ID_REGEX
		 */
		Pattern pattern = Pattern.compile(ID_REGEX + "foo" + "<");
		Matcher matcher = pattern.matcher(rootResponse);
		assertTrue(matcher.find());
		String id = matcher.group(1);

		// Delete the paste.
		queryMap = new HashMap<>();
		queryMap.put("id", List.of(id));
		String deleteResponse = pasteBinService.deleteHandler(queryMap);
		assertFalse(deleteResponse.contains("foo"));
	}

}
