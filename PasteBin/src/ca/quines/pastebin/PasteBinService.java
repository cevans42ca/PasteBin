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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.text.StringEscapeUtils;

/**
 * This class contains the business logic of the application to separate it from the socket handling and
 * to assist with testing.
 */
public class PasteBinService {

	private static final int DEFAULT_MAX_MAIN_ENTRIES = 20;
	private static final int DEFAULT_MAX_KEEP_DELETED_DAYS = 32;

	private static final long ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;
	private static final long KEEP_TIME_IN_MS = ONE_DAY_IN_MS * DEFAULT_MAX_KEEP_DELETED_DAYS;

	/* default */ static final String CONFIG_MAX_KEEP_DELETED_DAYS = "config.max_keep_deleted_days";
	/* default */ static final String CONFIG_MAX_MAIN_ENTRIES = "config.max_main_entries";

	/**
	 * This service is meant to be very low traffic and low volume.  We can get away with larger chunks of synchronized
	 * code.
	 */
	private final Object dataLock = new Object();

	private int maxMainEntries, maxKeepDeletedDays;

	// Define this at the class level
	private static final DateTimeFormatter ISO_NOBR_FORMATTER = 
			DateTimeFormatter.ofPattern("'<nobr>'yyyy-MM-dd'</nobr> <nobr>'HH:mm:ss'</nobr>'")
			.withZone(ZoneId.systemDefault());

	private File saveFile;

	/**
	 * The list of pasted items (the main list of items)
	 */
	private List<HistoryEntry> historyList;

	private List<HistoryEntry> pinnedHistoryList;
	private List<HistoryEntry> deletedHistoryList;

	/**
	 * Load the configuration, all three lists, and set a shutdown hook to save everything on JVM exit.
	 * 
	 * @param saveFile
	 */
	public PasteBinService(File saveFile, boolean saveOnExit) {
		synchronized(dataLock) {
			this.saveFile = saveFile;
			this.historyList = new ArrayList<>();
			this.pinnedHistoryList = new ArrayList<>();
			this.deletedHistoryList = new ArrayList<>();

			load();
		}

		if (saveOnExit) {
			Thread saveHook = new Thread(() -> save());
			Runtime.getRuntime().addShutdownHook(saveHook);
		}
	}

	/**
	 * Parse an integer value from a String with the given defaultValue.  Useful for reading data from a
	 * human-readable file.
	 * 
	 * @param props
	 * @param key
	 * @param defaultValue
	 * @return
	 */
	private int getIntWithDefault(Properties props, String key, int defaultValue) {
		if (props == null) {
			return defaultValue;
		}

		String stringValue = props.getProperty(key);
		try {
			return Integer.parseInt(stringValue);
		}
		catch (NullPointerException | NumberFormatException e) {
			System.err.println("Unable to parse value '" + stringValue + "' for key '" + key
				+ "' as an integer.  Using default value " + defaultValue + ".");
		}

		return defaultValue;
	}

	private void setDefaults(Properties props) {
		maxMainEntries = getIntWithDefault(props, CONFIG_MAX_MAIN_ENTRIES,
			DEFAULT_MAX_MAIN_ENTRIES);

		maxKeepDeletedDays = getIntWithDefault(props, CONFIG_MAX_KEEP_DELETED_DAYS,
			DEFAULT_MAX_KEEP_DELETED_DAYS);
	}

	/**
	 * Create a date that represents the specified number of milliseconds since the epoch.
	 * Useful for reading data from a human-readable file.
	 * 
	 * @param dateAsLongString
	 * 		A Long value in String form.  If null or 0, return the current timestamp.
	 * @param defaultDate
	 * @return
	 */
	private Instant convertToInstant(String dateAsLongString) {
		if (dateAsLongString == null || dateAsLongString.length() == 0) {
			return Instant.now();
		}

		Instant retVal;
		try {
			long dateAsLong = Long.parseLong(dateAsLongString);
			retVal = Instant.ofEpochMilli(dateAsLong);
		}
		catch (Exception e) {
			e.printStackTrace();
			return Instant.now();
		}

		return retVal;
	}

	/**
	 * @see #historyList
	 * 
	 * @param historyList
	 * @param props
	 * @param prefix
	 */
	private void loadHistoryList(List<HistoryEntry> historyList, Properties props, String prefix) {
		int index = 0;
		while (true) {
			String text = props.getProperty(prefix + "." + index + ".text");
			if (text == null) {
				break;
			}

			Instant createTs = convertToInstant(
				props.getProperty(prefix + "." + index + ".createDate"));

			Instant deletedTs = convertToInstant(
				props.getProperty(prefix + "." + index + ".deletedDate"));

			UUID uuid = convertToUUID(props.getProperty(
				prefix + "." + index + ".uuid"));

			String shortUrl = props.getProperty(prefix + "." + index + ".shortUrl", null);

			historyList.add(new HistoryEntry(text, createTs, deletedTs, uuid, shortUrl));

			index++;
		}
	}

	private void load() {
		System.out.println("Loading.");
		Properties props = new Properties();
		try (InputStream is = new FileInputStream(saveFile)) {
			props.load(is);
		}
		catch (FileNotFoundException e) {
			System.err.println("Unable to load configuration file '" + saveFile.getAbsolutePath() + "'.");
			props = null;
			setDefaults(props);
			return;
		}
		catch (IOException e) {
			e.printStackTrace();
			return;
		}

		setDefaults(props);

		loadHistoryList(historyList, props, "history");
		loadHistoryList(pinnedHistoryList, props, "pinnedHistory");
		loadHistoryList(deletedHistoryList, props, "deletedHistory");

		// Sort descending by putting h2 first in Long.compare.
		deletedHistoryList.sort(
				(HistoryEntry h1, HistoryEntry h2) -> Long.compare(h2.getDeletedTs().toEpochMilli(),
					h1.getDeletedTs().toEpochMilli()));

		System.out.println("Data loaded.");
	}

	/**
	 * Add an entry to the history list and delete any entries that were deleted earlier than the keep time.
	 * 
	 * @param newEntry
	 */
	private void addAndManageDeletedHistoryList(HistoryEntry newEntry) {
		deletedHistoryList.add(0, newEntry);

		long cutoff = System.currentTimeMillis() - KEEP_TIME_IN_MS;

		ListIterator<HistoryEntry> iter = deletedHistoryList.listIterator(deletedHistoryList.size());
		while (iter.hasPrevious()) {
			HistoryEntry entry = iter.previous();
			System.out.println("Comparing " + entry.getDeletedTs().toEpochMilli() + " to " + cutoff + ".");
			if (entry.getDeletedTs().toEpochMilli() < cutoff) {
				System.out.println("Removing old deleted entry.");
				iter.remove();
			}
			else {
				break;
			}
		}
	}

	private void checkHistoryListLength() {
		synchronized(dataLock) {
			if (historyList.size() > maxMainEntries) {
				HistoryEntry entry = historyList.remove(historyList.size() - 1);
				entry.setDeletedTs(Instant.now());
				addAndManageDeletedHistoryList(entry);
			}
		}
	}

	private void writeActiveHistory(Writer writer) throws IOException {
		HistorySnippetWriter hsw = (entry) ->  {
			writer.write(td("center", form("/pin", entry.getUuid(), "Pin")));
			writer.write(td("center", form("/delete", entry.getUuid(), "Delete")));
			writer.write(td("top", ISO_NOBR_FORMATTER.format(entry.getCreateTs())));
		};

		writeHistory(writer, historyList, hsw);
	}

	private void writeDeletedHistory(Writer writer) throws IOException {
		synchronized(dataLock) {
			if (deletedHistoryList.isEmpty()) {
				writer.write("There are no entries in the deleted list.");
			}
			else {
				HistorySnippetWriter hsw = (entry) ->  {
					writer.write(td("center", form("/undelete", entry.getUuid(), "Undelete")));
					writer.write(td("top", ISO_NOBR_FORMATTER.format(entry.getCreateTs())));
					writer.write(td("top", ISO_NOBR_FORMATTER.format(entry.getDeletedTs())));
				};

				writeHistory(writer, deletedHistoryList, hsw);
			}
		}
	}

	private void writePinnedHistory(Writer writer) throws IOException {
		HistorySnippetWriter hsw = (entry) ->  {
			writer.write(td("center", form("/deletePin", entry.getUuid(), "Delete")));
			writer.write(td("top", ISO_NOBR_FORMATTER.format(entry.getCreateTs())));
		};

		writeHistory(writer, pinnedHistoryList, hsw);
	}

	private void writeHistory(Writer writer, List<HistoryEntry> genericHistoryList, HistorySnippetWriter hsw) throws IOException {
		writeHistory(writer, genericHistoryList, hsw, null);
	}

	private void writeHistory(Writer writer, List<HistoryEntry> genericHistoryList, HistorySnippetWriter hsw, String header) throws IOException {
		synchronized(dataLock) {
			if (!genericHistoryList.isEmpty()) {
				writer.write("<table border='1' width='100%'>");
				if (header != null) {
					writer.write(header);
				}

				for (HistoryEntry entry : genericHistoryList) {
					writer.write("<tr><td id='text" + entry.getUuid() + "' class='top'>" + entry.getText() + "</td>");
					hsw.writeSnippet(entry);
					writer.write("</tr>");
				}
				writer.write("</table>");
			}
		}
	}

	public void writePage(Writer writer) throws IOException {
		writePage(writer, null, null);
	}

	public void writePage(Writer writer, String errorMessage, String infoMessage) throws IOException {
		writeHeader(writer);
		writer.write("<body>");
		writePinnedHistory(writer);
		writeForm(writer);

		if (errorMessage != null) {
			writer.write("<p><span style='color: #f00'>" + errorMessage + "</span></p>");
		}

		if (infoMessage != null) {
			writer.write("<p><span style='color: #0d0'>" + infoMessage + "</span></p>");
		}

		writeActiveHistory(writer);
		writer.write("</body>");
		writer.write("</html>");
	}

	private void writeHeader(Writer writer) throws IOException {
		writer.write("<html><head>");
		writer.write("<meta charset='UTF-8' name='viewport' content='width=640' initial-scale=1>");
		writer.write("<style>");
		writer.write("  tbody tr:nth-child(odd) {");
		writer.write("    background-color: #CEE8FF;");
		writer.write("    color: #000;");
		writer.write("  }");
		writer.write("\r\n");
		writer.write("  td.top {");
		writer.write("    vertical-align: top;");
		writer.write("  }");
		writer.write("\r\n");
		writer.write("  td.center {");
		writer.write("    text-align: center;");
		writer.write("    vertical-align: middle;");
		writer.write("  }");
		writer.write("\r\n");
		writer.write("  td input[type=button] {");
		writer.write("    vertical-align: middle;");
		writer.write("  }");
		writer.write("\r\n");
		writer.write("</style>");
		writer.write("<title>PasteBin</title>");
		writer.write("\r\n");
		writer.write("<script>");
		writer.write("\r\n");
		writer.write("	function submitForm() {");
		writer.write("\r\n");
		writer.write("		if (document.getElementById('fixPercent')) {");
		writer.write("\r\n");
		writer.write("			var str = document.getElementById('text').value; ");
		writer.write("\r\n");
		writer.write("			var replaced = str.replace(/%/g, '%25');");
		writer.write("\r\n");
		writer.write("			document.getElementById('text').value = replaced;");
		writer.write("\r\n");
		writer.write("		}");
		writer.write("\r\n");
		writer.write("		return true;");
		writer.write("\r\n");
		writer.write("	}");
		writer.write("\r\n");
		writer.write("</script>");
		writer.write("\r\n");
		writer.write("</head>");
	}

	private void writeForm(Writer writer) throws IOException {
		writer.write("<form method=\"POST\" action=\"/paste\" enctype=\"application/x-www-form-urlencoded\" onclick='submitForm()'>");
		writer.write("\r\n");
		writer.write("<textarea name='text' id='text' style='width: 100%' rows='5' cols='80' autofocus>");
		writer.write("</textarea>");
		writer.write("<br>");
		writer.write("\r\n");
		writer.write("<input type='checkbox' id='fixPercent' name='fixPercent' checked='true' value='true'>");
		writer.write("<label for='fixPercent'>Manually Fix Percent</label><br>");
		writer.write("<input type='checkbox' id='preformatted' name='preformatted' checked='true' value='true'>");
		writer.write("<label for='preformatted'>Preformatted</label><br>");
		writer.write("<input type='submit'>");
		writer.write("</form>");
		writer.write("<p><a href='/viewDeleted'>View Deleted</a></p>");
		writer.write("<p></p>");
		writer.write("<p><a href='/shortUrls'>View/Edit Short URLs</a></p>");
	}

	private void save() {
		if (saveFile == null) {
			System.out.println("Not saving:  no save location.");
			return;
		}

		System.out.println("Saving.");

		Properties props = new Properties();
		props.setProperty(CONFIG_MAX_MAIN_ENTRIES, "" + maxMainEntries);
		props.setProperty(CONFIG_MAX_KEEP_DELETED_DAYS, "" + maxKeepDeletedDays);

		synchronized(dataLock) {
			saveHistory(historyList, props, "history");
			saveHistory(pinnedHistoryList, props, "pinnedHistory");
			saveHistory(deletedHistoryList, props, "deletedHistory");
		}

		try (OutputStream os = new FileOutputStream(saveFile)) {
			props.store(os, "Storage File for PasteBin.java");
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void saveHistory(List<HistoryEntry> historyList, Properties props, String prefix) {
		int index = 0;
		for (HistoryEntry entry : historyList) {
			props.setProperty(prefix + "." + index + ".text", entry.getText());
			props.setProperty(prefix + "." + index + ".createDate",
				"" + entry.getCreateTs().toEpochMilli());

			if (entry.getDeletedTs() != null) {
				props.setProperty(prefix + "." + index + ".deletedDate",
					"" + entry.getDeletedTs().toEpochMilli());
			}

			if (entry.getShortUrl() != null) {
				props.setProperty(prefix + "." + index + ".shortUrl", entry.getShortUrl());
			}

			props.setProperty(prefix + "." + index + ".uuid",
				"" + entry.getUuid().toString());

			index++;
		}
	}

	private UUID convertToUUID(String uuidAsString) {
		try {
			if (uuidAsString != null) {
				return UUID.fromString(uuidAsString);
			}
		}
		catch (IllegalArgumentException e) {
			System.err.println("Unable to parse " + uuidAsString + " as a UUID.  Using a random one.");
			e.printStackTrace();
		}

		return UUID.randomUUID();
	}

	private String td(String cssClass, String innerHtml) {
		return "<td class='" + cssClass + "'>" + innerHtml + "</td>";
	}

	private String form(String action, UUID uuid, String submitValue) {
		return "<form action='" + action + "' method='POST'><input type='hidden' name='id' value='" + uuid.toString() + "'><input type='submit' value='" + submitValue + "'></form>";
	}

	private String input(UUID uuid, String value) {
		return "<input type='text' name='shortUrl" + uuid.toString() + "' value='" + (value==null ? "" : value) + "'>";
	}

	public String rootHandler(String requestPath) throws IOException {
		String htmlResponse = null; // This will hold our prepared output
		OUTER: synchronized(dataLock) {
			java.io.StringWriter sw = new java.io.StringWriter();
			if (requestPath.startsWith("/") && !requestPath.equals("/")) {
				requestPath = requestPath.substring(1);

				for (HistoryEntry entry : pinnedHistoryList) {
					if (requestPath.equals(entry.getShortUrl())) {
						System.out.println("Found " + entry.getText());
						htmlResponse = entry.getText();
						break OUTER;
					}
				}

				for (HistoryEntry entry : historyList) {
					if (requestPath.equals(entry.getShortUrl())) {
						System.out.println("Found " + entry.getText());
						htmlResponse = entry.getText();
						break OUTER;
					}
				}
			}

			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String pasteHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw);
				return sw.toString();
			}

			boolean preformatted = false;
			List<String> preValue = queryMap.get("preformatted");
			if (preValue != null && preValue.size() == 1) {
				preformatted = true;
			}

			List<String> textValue = queryMap.get("text");
			System.out.println(textValue);
			if (textValue != null && textValue.size() == 1) {
				String text = textValue.get(0);
				System.out.println(text);

				text = java.net.URLDecoder.decode(text, "UTF-8");
				text = StringEscapeUtils.escapeHtml4(text);
				if (preformatted) {
					text = "<pre>" + text + "</pre>";
				}

				historyList.add(0, new HistoryEntry(text));
				checkHistoryListLength();
			}

			StringWriter sw = new StringWriter();
			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String deleteHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw);
				return sw.toString();
			}

			List<String> idValue = queryMap.get("id");
			if (idValue != null && idValue.size() == 1) {
				try {
					UUID uuid = UUID.fromString(idValue.get(0));
					Iterator<HistoryEntry> entryIter = historyList.iterator();
					while (entryIter.hasNext()) {
						HistoryEntry entry = entryIter.next();
						if (entry.getUuid().equals(uuid)) {
							entryIter.remove();
							entry.setDeletedTs(Instant.now());
							addAndManageDeletedHistoryList(entry);
							break;
						}
					}
				}
				catch (IndexOutOfBoundsException | NumberFormatException e) {
					e.printStackTrace();
					// Fall through and return the usual response.
				}
			}

			StringWriter sw = new StringWriter();
			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String undeleteContextHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw);
				return sw.toString();
			}

			List<String> idValue = queryMap.get("id");
			if (idValue != null && idValue.size() == 1) {
				try {
					UUID uuid = UUID.fromString(idValue.get(0));
					Iterator<HistoryEntry> entryIter = deletedHistoryList.iterator();
					while (entryIter.hasNext()) {
						HistoryEntry entry = entryIter.next();
						if (entry.getUuid().equals(uuid)) {
							entryIter.remove();
							entry.setDeletedTs(null);
							historyList.add(0, entry);
							checkHistoryListLength();
							break;
						}
					}
				}
				catch (IndexOutOfBoundsException | NumberFormatException e) {
					e.printStackTrace();
					// Fall through and return the usual response.
				}
			}

			StringWriter sw = new StringWriter();
			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String deletePinContextHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw);
				return sw.toString();
			}

			List<String> idValue = queryMap.get("id");
			if (idValue != null && idValue.size() == 1) {
				try {
					UUID uuid = UUID.fromString(idValue.get(0));
					Iterator<HistoryEntry> entryIter = pinnedHistoryList.iterator();
					while (entryIter.hasNext()) {
						HistoryEntry entry = entryIter.next();
						if (entry.getUuid().equals(uuid)) {
							entryIter.remove();
							entry.setDeletedTs(Instant.now());
							addAndManageDeletedHistoryList(entry);
							break;
						}
					}
				}
				catch (NumberFormatException e) {
					e.printStackTrace();
					// Fall through and return the usual response.
				}
			}

			StringWriter sw = new StringWriter();
			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String pinContextHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw);
				return sw.toString();
			}

			List<String> idValue = queryMap.get("id");
			if (idValue != null && idValue.size() == 1) {
				try {
					UUID uuid = UUID.fromString(idValue.get(0));
					Iterator<HistoryEntry> entryIter = historyList.iterator();
					while (entryIter.hasNext()) {
						HistoryEntry entry = entryIter.next();
						if (entry.getUuid().equals(uuid)) {
							entryIter.remove();
							pinnedHistoryList.add(0, entry);
							break;
						}
					}
				}
				catch (NumberFormatException e) {
					e.printStackTrace();
					// Fall through and return the usual response.
				}
			}

			StringWriter sw = new StringWriter();
			writePage(sw);
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String viewDeletedContextHandler() throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			StringWriter sw = new StringWriter();
			writeHeader(sw);
			sw.write("<body>");
			sw.write("<p><a href='/'>Home</a></p>");
			writeDeletedHistory(sw);
			sw.write("</body>");
			sw.write("</html>");
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String shortUrlDisplayHandler() throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			// String requestMethod = he.getRequestMethod();
			// Headers requestHeaders = he.getRequestHeaders();

			StringWriter sw = new StringWriter();
			writeHeader(sw);
			sw.write("<body>");

			sw.write("<form action='/updateShortUrls' method='POST'>");

			HistorySnippetWriter hsw = (entry) ->  {
				sw.write(td("center", input(entry.getUuid(), entry.getShortUrl())));
				sw.write(td("top", ISO_NOBR_FORMATTER.format(entry.getCreateTs())));
			};

			String header = "<tr><th>Text</th><th>Short URL</th><th>Created Date</th></tr>";

			sw.write("<h2>Pinned Items</h2>");
			writeHistory(sw, pinnedHistoryList, hsw, header);
			sw.write("<input type='submit'>");

			sw.write("<h2>Unpinned Items</h2>");
			writeHistory(sw, historyList, hsw, header);
			sw.write("<input type='submit'>");

			sw.write("</form>");

			sw.write("</body>");
			sw.write("</html>");

			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

	public String updateShortUrlHandler(Map<String, List<String>> queryMap) throws IOException {
		String htmlResponse = null;
		synchronized(dataLock) {
			if (queryMap == null) {
				StringWriter sw = new StringWriter();
				writePage(sw, "Please try your request again.", null);
				return sw.toString();
			}

			HashMap<UUID, HistoryEntry> entryMap = new HashMap<>();

			for (HistoryEntry entry : pinnedHistoryList) {
				entryMap.put(entry.getUuid(), entry);
			}

			for (HistoryEntry entry : historyList) {
				entryMap.put(entry.getUuid(), entry);
			}

			int count = 0;
			for (Map.Entry<String, List<String>> entry : queryMap.entrySet()) {
				if (!entry.getKey().startsWith("shortUrl")) {
					continue;
				}

				String bareKey = entry.getKey().substring("shortUrl".length());
				HistoryEntry historyEntry = entryMap.get(UUID.fromString(bareKey));

				if (historyEntry == null) {
					continue;
				}

				if (entry.getValue() != null && entry.getValue().size() == 1) {
					String simpleValue = entry.getValue().get(0);
					if (simpleValue.length() > 0) {
						System.out.println(entry.getKey() + "=" + simpleValue);
						historyEntry.setShortUrl(simpleValue);
						count++;
					}
					else {
						historyEntry.setShortUrl("");
					}
				}
			}

			StringWriter sw = new StringWriter();
			writePage(sw, null, "Number of short URLs set (total):  " + count + ".");
			htmlResponse = sw.toString();
		}

		return htmlResponse;
	}

}
