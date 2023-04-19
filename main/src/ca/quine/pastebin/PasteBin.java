package ca.quine.pastebin;

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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;

import java.nio.charset.StandardCharsets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.UnknownHostException;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.text.StringEscapeUtils;


public class PasteBin {

	private static final int DEFAULT_MAX_MAIN_ENTRIES = 20;
	private static final int DEFAULT_MAX_KEEP_DELETED_DAYS = 32;

	private static final long ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;
	private static final long KEEP_TIME_IN_MS = ONE_DAY_IN_MS * DEFAULT_MAX_KEEP_DELETED_DAYS;

	private static final String SAVE_FILENAME = ".pastebin";
	private static final String DEFAULT_INET_SEARCH = "192.168.";

	private static final QuerySplit querySplit = new QuerySplit();

	private SimpleDateFormat dateFormatter = new SimpleDateFormat("'<nobr>'yyyy-MM-dd'</nobr> <nobr>'HH:mm:ss'</nobr>'");
 	private File saveFile;
	private HttpServer httpServer;
	private List<HistoryEntry> historyList;
	private List<HistoryEntry> pinnedHistoryList;
	private List<HistoryEntry> deletedHistoryList;

	private int maxMainEntries, maxKeepDeletedDays;

	public String getAddressFullDisplay(NetworkInterface netInterface, InetAddress address) {
		return netInterface.getName() + " / " + netInterface.getDisplayName() + " / " + address.getHostAddress();
	}

	public PasteBin(File saveFile, String interfaceSpec) throws UnknownHostException, IOException, IllegalArgumentException {
		this.saveFile = saveFile;
		this.historyList = new ArrayList<>();
		this.pinnedHistoryList = new ArrayList<>();
		this.deletedHistoryList = new ArrayList<>();

		load();

		Thread saveHook = new Thread(() -> save());
		Runtime.getRuntime().addShutdownHook(saveHook);

		List<InetAddress> foundInterfaceList = new ArrayList<>();
		InetAddress foundInterface = null;
		Enumeration interfaceEnum = NetworkInterface.getNetworkInterfaces();
		while(interfaceEnum.hasMoreElements()) {
			NetworkInterface netInterface = (NetworkInterface) interfaceEnum.nextElement();
			Enumeration addressesEnum = netInterface.getInetAddresses();
			while(addressesEnum.hasMoreElements())
			{
				InetAddress address = (InetAddress) addressesEnum.nextElement();
				String addressFullDisplay = getAddressFullDisplay(netInterface, address);
				System.out.println(addressFullDisplay);

				if (addressFullDisplay.indexOf(interfaceSpec) > -1) {
					foundInterfaceList.add(address);
				}
			}
		}

		if (foundInterfaceList.size() == 0) {
			System.err.println("No match for interface " + interfaceSpec);
			throw new IllegalArgumentException();
		}
		else if (foundInterfaceList.size() == 1) {
			foundInterface = foundInterfaceList.get(0);
		}
		else if (foundInterfaceList.size() > 1) {
			System.err.println();
			System.err.println("Too many matches for interface " + interfaceSpec + ":");
			for (InetAddress interfaceEntry : foundInterfaceList) {
				System.out.println(interfaceEntry.getHostAddress());
			}
			throw new IllegalArgumentException();
		}

		System.out.println("Listening for connections to:  " + foundInterface + ".");

		InetSocketAddress inetSocketAddress = new InetSocketAddress(foundInterface, 8080);
		this.httpServer = HttpServer.create(inetSocketAddress, 10);
		this.httpServer.createContext("/", (he) -> rootContextHandler(he));
		this.httpServer.createContext("/paste", (he) -> pasteContextHandler(he));
		this.httpServer.createContext("/pin", (he) -> pinContextHandler(he));
		this.httpServer.createContext("/delete", (he) -> deleteContextHandler(he));
		this.httpServer.createContext("/undelete", (he) -> undeleteContextHandler(he));
		this.httpServer.createContext("/deletePin", (he) -> deletePinContextHandler(he));
		this.httpServer.createContext("/viewDeleted", (he) -> viewDeletedContextHandler(he));
		this.httpServer.createContext("/shortUrls", (he) -> shortUrls(he));
		this.httpServer.createContext("/updateShortUrls", (he) -> updateShortUrls(he));
	}

	public static void main(String[] args) throws IOException {
		File storageFile = new File(System.getProperty("user.home"), SAVE_FILENAME);
		System.out.println(storageFile.getAbsolutePath());

		if (args.length > 1) {
			System.out.println("This program takes zero or one argument:  the interface name or IP to listen on.");
			return;
		}

		String inetSearch;
		if (args.length == 0) {
			inetSearch = DEFAULT_INET_SEARCH;
		}
		else {
			inetSearch = args[0];
		}

		try {
			PasteBin pasteBin = new PasteBin(storageFile, inetSearch);
			pasteBin.httpServer.start();
		}
		catch (IllegalArgumentException e) {
			// We already printed out an error.
		}
	}

	private void sendResponseHeadersOK(HttpExchange he) throws IOException {
		he.sendResponseHeaders(200, 0);
		he.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
	}

	private void rootContextHandler(HttpExchange he) {
		try {
			String requestPath = he.getRequestURI().getPath();
			if (requestPath.startsWith("/") && !requestPath.equals("/")) {
				requestPath = requestPath.substring(1);

				for (HistoryEntry entry : pinnedHistoryList) {
					if (requestPath.equals(entry.getShortUrl())) {
						System.out.println("Found " + entry.getText());
						sendText(he, entry.getText());
						return;
					}
				}

				for (HistoryEntry entry : historyList) {
					if (requestPath.equals(entry.getShortUrl())) {
						System.out.println("Found " + entry.getText());
						sendText(he, entry.getText());
						return;
					}
				}
			}

			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendText(HttpExchange he, String text) throws IOException {
		he.sendResponseHeaders(200, 0);
		he.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");

		OutputStream os = he.getResponseBody();
		try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
			bw.write(text);
		}
	}

	private void writeHeader(BufferedWriter bw) throws IOException {
		bw.write("<html><head>");
		bw.write("<meta charset='UTF-8' name='viewport' content='width=640' initial-scale=1>");
		bw.write("<style>");
		bw.write("  tbody tr:nth-child(odd) {");
		bw.write("    background-color: #CEE8FF;");
		bw.write("    color: #000;");
		bw.write("  }");
		bw.newLine();
		bw.write("  td.top {");
		bw.write("    vertical-align: top;");
		bw.write("  }");
		bw.newLine();
		bw.write("  td.center {");
		bw.write("    text-align: center;");
		bw.write("    vertical-align: middle;");
		bw.write("  }");
		bw.newLine();
		bw.write("  td input[type=button] {");
		bw.write("    vertical-align: middle;");
		bw.write("  }");
		bw.newLine();
		bw.write("</style>");
		bw.write("<title>PasteBin</title>");
		bw.newLine();
		bw.write("<script>");
		bw.newLine();
		bw.write("	function submitForm() {");
		bw.newLine();
		bw.write("		if (document.getElementById('fixPercent')) {");
		bw.newLine();
		bw.write("			var str = document.getElementById('text').value; ");
		bw.newLine();
		bw.write("			var replaced = str.replace(/%/g, '%25');");
		bw.newLine();
		bw.write("			document.getElementById('text').value = replaced;");
		bw.newLine();
		bw.write("		}");
		bw.newLine();
		bw.write("		return true;");
		bw.newLine();
		bw.write("	}");
		bw.newLine();
		bw.write("</script>");
		bw.newLine();
		bw.write("</head>");
	}

	private void writeForm(BufferedWriter bw) throws IOException {
		bw.write("<form method=\"POST\" action=\"/paste\" enctype=\"application/x-www-form-urlencoded\" onclick='submitForm()'>");
		bw.newLine();
		bw.write("<textarea name='text' id='text' style='width: 100%' rows='5' cols='80' autofocus>");
		bw.write("</textarea>");
		bw.write("<br>");
		bw.newLine();
		bw.write("<input type='checkbox' id='fixPercent' name='fixPercent' checked='true' value='true'>");
		bw.write("<label for='fixPercent'>Manually Fix Percent</label><br>");
		bw.write("<input type='checkbox' id='preformatted' name='preformatted' checked='true' value='true'>");
		bw.write("<label for='preformatted'>Preformatted</label><br>");
		bw.write("<input type='submit'>");
		bw.write("</form>");
		bw.write("<p><a href='/viewDeleted'>View Deleted</a></p>");
		bw.write("<p></p>");
		bw.write("<p><a href='/shortUrls'>View/Edit Short URLs</a></p>");
	}

	private void writeActiveHistory(BufferedWriter bw) throws IOException {
		HistorySnippetWriter hsw = (entry) ->  {
			bw.write(td("center", form("/pin", entry.getUuid(), "Pin")));
			bw.write(td("center", form("/delete", entry.getUuid(), "Delete")));
			bw.write(td("top", dateFormatter.format(entry.getCreateDate())));
		};

		writeHistory(bw, historyList, hsw);
	}

	private void writeDeletedHistory(BufferedWriter bw) throws IOException {
		if (deletedHistoryList.isEmpty()) {
			bw.write("There are no entries in the deleted list.");
		}
		else {
			HistorySnippetWriter hsw = (entry) ->  {
				bw.write(td("center", form("/undelete", entry.getUuid(), "Undelete")));
				bw.write(td("top", dateFormatter.format(entry.getCreateDate())));
				bw.write(td("top", dateFormatter.format(entry.getDeletedDate())));
			};

			writeHistory(bw, deletedHistoryList, hsw);
		}
	}

	private void writePinnedHistory(BufferedWriter bw) throws IOException {
		HistorySnippetWriter hsw = (entry) ->  {
			bw.write(td("center", form("/deletePin", entry.getUuid(), "Delete")));
			bw.write(td("top", dateFormatter.format(entry.getCreateDate())));
		};

		writeHistory(bw, pinnedHistoryList, hsw);
	}

	private void writeHistory(BufferedWriter bw, List<HistoryEntry> genericHistoryList, HistorySnippetWriter hsw) throws IOException {
		writeHistory(bw, genericHistoryList, hsw, null);
	}

	private void writeHistory(BufferedWriter bw, List<HistoryEntry> genericHistoryList, HistorySnippetWriter hsw, String header) throws IOException {
		if (!genericHistoryList.isEmpty()) {
			synchronized(dateFormatter) {
				bw.write("<table border='1' width='100%'>");
				if (header != null) {
					bw.write(header);
				}

				int index = 0;
				for (HistoryEntry entry : genericHistoryList) {
					bw.write("<tr><td id='text" + entry.getUuid() + "' class='top'>" + entry.getText() + "</td>");
					hsw.writeSnippet(entry);
					bw.write("</tr>");
					index++;
				}
				bw.write("</table>");
			}
		}
	}

	private void pasteContextHandler(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
			}

			// String queryParms = requestUri.getQuery();

			boolean preformatted = false;
			List<String> preValue = queryMap.get("preformatted");
			if (preValue != null && preValue.size() == 1) {
				preformatted = true;
			}

			Headers requestHeaders = he.getRequestHeaders();
			for (Map.Entry<String, List<String>> entrySet : requestHeaders.entrySet()) {
				for (String value : entrySet.getValue()) {
					System.out.println(entrySet.getKey() + "=" + value);
				}
			}

			List<String> textValue = queryMap.get("text");
			System.out.println(textValue);
			if (textValue != null && textValue.size() == 1) {
				String text = textValue.get(0);
				// URLDecoder is specifically for URL encoded form data but seems to choke on %5C (backslash).
				System.out.println(text);
				//text = text.replace("%5C","\\");
				text = java.net.URLDecoder.decode(text, "UTF-8");

				text = StringEscapeUtils.escapeHtml4(text);
				if (preformatted) {
					text = "<pre>" + text + "</pre>";
				}

				historyList.add(0, new HistoryEntry(text));
				checkHistoryListLength();
			}

			// getResponseHeaders
			System.out.println("Sending response headers.");

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			System.out.println("Sending response body.");

			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			try {
				sendErrorResponse(he, 400, e.getMessage());
			}
			catch (IOException ioe) {
				System.err.println("Unable to write error response while handling an exception.");
				ioe.printStackTrace();
			}
		}
	}

	private void deleteContextHandler(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
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
							entry.setDeletedDate(new Date());
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

			Headers requestHeaders = he.getRequestHeaders();
			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void undeleteContextHandler(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
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
							entry.setDeletedDate(null);
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

			Headers requestHeaders = he.getRequestHeaders();
			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void deletePinContextHandler(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
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
							entry.setDeletedDate(new Date());
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

			Headers requestHeaders = he.getRequestHeaders();
			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void pinContextHandler(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
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

			Headers requestHeaders = he.getRequestHeaders();
			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void viewDeletedContextHandler(HttpExchange he) {
		try {
			String requestMethod = he.getRequestMethod();
			URI requestUri = he.getRequestURI();
			System.out.println(requestUri);

			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writeHeader(bw);
				bw.write("<body>");
				bw.write("<p><a href='/'>Home</a></p>");
				writeDeletedHistory(bw);
				bw.write("</body>");
				bw.write("</html>");
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writePage(BufferedWriter bw) throws IOException {
		writePage(bw, null, null);
	}

	private void writePage(BufferedWriter bw, String errorMessage, String infoMessage) throws IOException {
		writeHeader(bw);
		bw.write("<body>");
		writePinnedHistory(bw);
		writeForm(bw);

		if (errorMessage != null) {
			bw.write("<p><span style='color: #f00'>" + errorMessage + "</span></p>");
		}

		if (infoMessage != null) {
			bw.write("<p><span style='color: #0d0'>" + infoMessage + "</span></p>");
		}

		writeActiveHistory(bw);
		bw.write("</body>");
		bw.write("</html>");
	}

	private void save() {
		if (saveFile == null) {
			System.out.println("Not saving:  no save location.");
			return;
		}

		System.out.println("Saving.");

		Properties props = new Properties();

		props.setProperty("config.max_main_entries", "" + maxMainEntries);
		props.setProperty("config.max_keep_deleted_days", "" + maxKeepDeletedDays);

		saveHistory(historyList, props, "history");
		saveHistory(pinnedHistoryList, props, "pinnedHistory");
		saveHistory(deletedHistoryList, props, "deletedHistory");

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
				"" + entry.getCreateDate().getTime());

			if (entry.getDeletedDate() != null) {
				props.setProperty(prefix + "." + index + ".deletedDate",
					"" + entry.getDeletedDate().getTime());
			}

			if (entry.getShortUrl() != null) {
				props.setProperty(prefix + "." + index + ".shortUrl", entry.getShortUrl());
			}

			props.setProperty(prefix + "." + index + ".uuid",
				"" + entry.getUuid().toString());

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
				(HistoryEntry h1, HistoryEntry h2) -> Long.compare(h2.getDeletedDate().getTime(),
					h1.getDeletedDate().getTime()));

		System.out.println("Data loaded.");
	}

	private void setDefaults(Properties props) {
		maxMainEntries = getIntWithDefault(props, "config.max_main_entries",
			DEFAULT_MAX_MAIN_ENTRIES);

		maxKeepDeletedDays = getIntWithDefault(props, "config.max_keep_deleted_days",
			DEFAULT_MAX_KEEP_DELETED_DAYS);
	}

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

	private void loadHistoryList(List<HistoryEntry> historyList, Properties props, String prefix) {
		int index = 0;
		while (true) {
			String text = props.getProperty(prefix + "." + index + ".text");
			if (text == null) {
				break;
			}

			Date createDate = convertToDate(
				props.getProperty(prefix + "." + index + ".createDate"),
				new Date());

			Date deletedDate = convertToDate(
				props.getProperty(prefix + "." + index + ".deletedDate"),
				null);

			UUID uuid = convertToUUID(props.getProperty(
				prefix + "." + index + ".uuid"));

			String shortUrl = props.getProperty(prefix + "." + index + ".shortUrl", null);

			historyList.add(new HistoryEntry(text, createDate, deletedDate, uuid, shortUrl));

			index++;
		}
	}

	private Date convertToDate(String dateAsLongString, Date defaultDate) {
		Date retVal = defaultDate;

		if (dateAsLongString == null || dateAsLongString.length() == 0) {
			return retVal;
		}

		try {
			long dateAsLong = Long.parseLong(dateAsLongString);
			retVal = new Date(dateAsLong);
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return retVal;
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

	private void checkHistoryListLength() {
		if (historyList.size() > maxMainEntries) {
			HistoryEntry entry = historyList.remove(historyList.size() - 1);
			entry.setDeletedDate(new Date());
			addAndManageDeletedHistoryList(entry);
		}
	}

	private void sendErrorResponse(HttpExchange he, int errorCode, String errorMessage)
			throws IOException
	{
		he.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		he.sendResponseHeaders(errorCode, 0);

		OutputStream os = he.getResponseBody();
		try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
			writePage(bw, errorMessage, null);
		}
	}

	private Map<String, List<String>> handlePost(HttpExchange he) throws IOException {
		String requestMethod = he.getRequestMethod();
		if (!"POST".equals(requestMethod)) {
			System.out.println("Request method " + requestMethod + " is not allowed for updating.");
			sendErrorResponse(he, 400, "Only POST is allowed for updating.");

			return null;
		}

		URI requestUri = he.getRequestURI();
		System.out.println(requestUri);

		Headers requestHeaders = he.getRequestHeaders();
		InputStream is = he.getRequestBody();
		String line = null;
		Map<String, List<String>> queryMap = null;
		try {
			int bufferSize = 1024;
			char[] buffer = new char[bufferSize];
			StringBuilder out = new StringBuilder();
			Reader in = new InputStreamReader(is, StandardCharsets.UTF_8);
			for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
			     out.append(buffer, 0, numRead);
			}

			System.out.println(out.toString());
			queryMap = querySplit.splitQuery(out.toString());
		}
		catch (IOException e) {
			e.printStackTrace();
			sendErrorResponse(he, 500, "Internal Error.  Check the logs on the host.");

			return null;
		}

		return queryMap;
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

	private void addAndManageDeletedHistoryList(HistoryEntry newEntry) {
		deletedHistoryList.add(0, newEntry);

		long cutoff = System.currentTimeMillis() - KEEP_TIME_IN_MS;

		ListIterator<HistoryEntry> iter = deletedHistoryList.listIterator(deletedHistoryList.size());
		while (iter.hasPrevious()) {
			HistoryEntry entry = iter.previous();
			System.out.println("Comparing " + entry.getDeletedDate().getTime() + " to " + cutoff + ".");
			if (entry.getDeletedDate().getTime() < cutoff) {
				System.out.println("Removing old deleted entry.");
				iter.remove();
			}
			else {
				break;
			}
		}
	}

	private void shortUrls(HttpExchange he) {
		try {
			String requestMethod = he.getRequestMethod();
			Headers requestHeaders = he.getRequestHeaders();
			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			// getResponseHeaders
			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writeHeader(bw);
				bw.write("<body>");

				bw.write("<form action='/updateShortUrls' method='POST'>");

				HistorySnippetWriter hsw = (entry) ->  {
					bw.write(td("center", input(entry.getUuid(), entry.getShortUrl())));
					bw.write(td("top", dateFormatter.format(entry.getCreateDate())));
				};

				String header = "<tr><th>Text</th><th>Short URL</th><th>Created Date</th></tr>";

				bw.write("<h2>Pinned Items</h2>");
				writeHistory(bw, pinnedHistoryList, hsw, header);
				bw.write("<input type='submit'>");

				bw.write("<h2>Unpinned Items</h2>");
				writeHistory(bw, historyList, hsw, header);
				bw.write("<input type='submit'>");

				bw.write("</form>");

				bw.write("</body>");
				bw.write("</html>");
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void updateShortUrls(HttpExchange he) {
		try {
			Map<String, List<String>> queryMap = handlePost(he);

			if (queryMap == null) {
				return;
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

			// getResponseHeaders
			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				writePage(bw, null, "Number of short URLs set (total):  " + count + ".");
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

}

