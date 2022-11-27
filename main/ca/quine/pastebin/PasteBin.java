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

import java.beans.Encoder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.text.StringEscapeUtils;


public class PasteBin {

	private static final int DEFAULT_MAX_MAIN_ENTRIES = 20;
	private static final int DEFAULT_MAX_KEEP_DELETED_DAYS = 32;

	private static final long ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;
	private static final long KEEP_TIME_IN_MS = ONE_DAY_IN_MS * DEFAULT_MAX_KEEP_DELETED_DAYS;

	private static final QuerySplit querySplit = new QuerySplit();

	private SimpleDateFormat dateFormatter = new SimpleDateFormat("'<nobr>'yyyy-MM-dd'</nobr> <nobr>'HH:mm:ss'</nobr>'");
 	private String saveLocation;
	private HttpServer httpServer;
	private List<HistoryEntry> historyList;
	private List<HistoryEntry> pinnedHistoryList;
	private List<HistoryEntry> deletedHistoryList;

	private int maxMainEntries, maxKeepDeletedDays;

	public PasteBin(String saveLocation, String interfaceSpec) throws UnknownHostException, IOException {
		this.saveLocation = saveLocation;
		this.historyList = new ArrayList<>();
		this.pinnedHistoryList = new ArrayList<>();
		this.deletedHistoryList = new ArrayList<>();

		load();

		Thread saveHook = new Thread(() -> save());
		Runtime.getRuntime().addShutdownHook(saveHook);

		InetAddress foundInterface = null;
		Enumeration interfaceEnum = NetworkInterface.getNetworkInterfaces();
		while(interfaceEnum.hasMoreElements()) {
			NetworkInterface netInterface = (NetworkInterface) interfaceEnum.nextElement();
			Enumeration addressesEnum = netInterface.getInetAddresses();
			while(addressesEnum.hasMoreElements())
			{
				InetAddress address = (InetAddress) addressesEnum.nextElement();
				System.out.println(netInterface.getName() + " / " + netInterface.getDisplayName() + " / " +
						address.getHostAddress());

				if (address.getHostAddress().equals(interfaceSpec)) {
					foundInterface = address;
				}
			}
		}

		if (foundInterface == null) {
			System.err.println("No match for interface " + interfaceSpec);
			return;
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
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.out.println("This program takes two arguments:  the location of the save file and the interface name or IP to listen on.");
			return;
		}

		PasteBin pasteBin = new PasteBin(args[0], args[1]);
		pasteBin.httpServer.start();
	}

	private void sendResponseHeadersOK(HttpExchange he) throws IOException {
		he.sendResponseHeaders(200, 0);
		he.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
	}

	private void rootContextHandler(HttpExchange he) {
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
				writePage(bw);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
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
		if (!genericHistoryList.isEmpty()) {
			synchronized(dateFormatter) {
				bw.write("<table border='1' width='100%'>");
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
		writePage(bw, null);
	}

	private void writePage(BufferedWriter bw, String errorMessage) throws IOException {
		writeHeader(bw);
		bw.write("<body>");
		writePinnedHistory(bw);
		writeForm(bw);
		if (errorMessage != null) {
			bw.write("<p><span style='color: #f00'>" + errorMessage + "</span></p>");
		}
		writeActiveHistory(bw);
		bw.write("</body>");
		bw.write("</html>");
	}

	private void save() {
		if (saveLocation == null) {
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

		try (OutputStream os = new FileOutputStream(saveLocation)) {
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

			props.setProperty(prefix + "." + index + ".uuid",
				"" + entry.getUuid().toString());

			index++;
		}
	}

	private void load() {
		System.out.println("Loading.");
		Properties props = new Properties();
		try (InputStream is = new FileInputStream(saveLocation)) {
			props.load(is);
		}
		catch (FileNotFoundException e) {
			System.err.println("Unable to load configuration file '" + saveLocation + "'.");
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

			historyList.add(new HistoryEntry(text, createDate, deletedDate, uuid));

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
			writePage(bw, errorMessage);
		}
	}

	private Map<String, List<String>> handlePost(HttpExchange he) throws IOException {
		String requestMethod = he.getRequestMethod();
		if (!"POST".equals(requestMethod)) {
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

}

