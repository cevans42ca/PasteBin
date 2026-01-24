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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
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

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles interfacing with the console as well as the network.
 * Console handling will use System.out and System.in, while logging is done with JDK 1.4 logging.
 * These tasks might be split in the future.
 */
public class PasteBin {

	private static final Logger LOGGER = Logger.getLogger(PasteBin.class.getName());

	private static final String SAVE_FILENAME = ".pastebin";
	private static final String DEFAULT_INET_SEARCH = "192.168.";

	private static final QuerySplit querySplit = new QuerySplit();

	private static final boolean SAVE_ON_EXIT = true;
	
	private HttpServer httpServer;
	private PasteBinService pasteBinService;

	public String getAddressFullDisplay(NetworkInterface netInterface, InetAddress address) {
		return netInterface.getName() + " / " + netInterface.getDisplayName() + " / " + address.getHostAddress();
	}

	public PasteBin(File saveFile, String interfaceSpec) throws UnknownHostException, IOException, IllegalArgumentException {
		pasteBinService = new PasteBinService(saveFile, SAVE_ON_EXIT);

		List<InetAddress> foundInterfaceList = new ArrayList<>();
		InetAddress foundInterface = null;
		Enumeration<NetworkInterface> interfaceEnum = NetworkInterface.getNetworkInterfaces();
		while(interfaceEnum.hasMoreElements()) {
			NetworkInterface netInterface = (NetworkInterface) interfaceEnum.nextElement();
			Enumeration<InetAddress> addressesEnum = netInterface.getInetAddresses();
			while(addressesEnum.hasMoreElements())
			{
				InetAddress address = addressesEnum.nextElement();
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
		System.out.println("Using save file:  '" + storageFile.getAbsolutePath() + "'.");

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

		PasteBin pasteBin = null;
		try {
			pasteBin = new PasteBin(storageFile, inetSearch);
			pasteBin.httpServer.start();
		}
		catch (IllegalArgumentException e) {
			// We already printed out an error.
			return;
		}

		// Ctrl+C works at the console, but not in Eclipse.  Allow a shutdown in Eclipse with "shutdown" at stdin.
	    Thread consoleListener = new Thread(() -> {
	        System.out.println("Console listener started. Type 'shutdown' to stop the server.");
	        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
	            String line;
	            while ((line = reader.readLine()) != null) {
	                if ("shutdown".equalsIgnoreCase(line.trim())) {
	                    System.out.println("Shutdown command received. Stopping server...");
	                    System.exit(0); // This triggers the save() shutdown hook.
	                    break;
	                }
	            }
	        } catch (IOException e) {
	            System.err.println("Error reading from console: " + e.getMessage());
	        }
	    });
	    consoleListener.setDaemon(false); // Ensure this thread keeps the JVM alive
	    consoleListener.start();
	}

	private void sendResponseHeadersOK(HttpExchange he) throws IOException {
		he.sendResponseHeaders(200, 0);
		he.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
	}

	/**
	 * Read and discard the input from the given HttpExchange object.
	 * There are cases where the HttpExchange object expects us to read the lines, even if we
	 * don't process them.  If we don't, it might cause I/O to block.
	 * 
	 * @param he
	 * @throws IOException
	 */
	private void slurpInput(HttpExchange he) throws IOException {
		InputStream is = he.getRequestBody();
		String line = null;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
			while ((line = br.readLine()) != null) {
				LOGGER.finer(line);
			}
		}
	}

	private void rootContextHandler(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.rootHandler(he.getRequestURI().getPath());

			InputStream is = he.getRequestBody();
			String line = null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				while ((line = br.readLine()) != null) {
					System.out.println(line);
				}
			}

			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void pasteContextHandler(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.pasteHandler(handlePost(he));

			LOGGER.fine("Sending response headers.");
			sendResponseHeadersOK(he);

			LOGGER.fine("Sending response body.");
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
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
			String htmlResponse = pasteBinService.deleteHandler(handlePost(he));
			slurpInput(he);

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void undeleteContextHandler(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.undeleteContextHandler(handlePost(he));
			slurpInput(he);

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void deletePinContextHandler(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.deletePinContextHandler(handlePost(he));
			slurpInput(he);

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void pinContextHandler(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.pinContextHandler(handlePost(he));
			slurpInput(he);

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void viewDeletedContextHandler(HttpExchange he) {
		try {
			URI requestUri = he.getRequestURI();
			System.out.println(requestUri);

			String htmlResponse = pasteBinService.viewDeletedContextHandler();
			slurpInput(he);

			sendResponseHeadersOK(he);
			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendErrorResponse(HttpExchange he, int errorCode, String errorMessage)
			throws IOException
	{
		he.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		he.sendResponseHeaders(errorCode, 0);

		OutputStream os = he.getResponseBody();
		try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
			pasteBinService.writePage(bw, errorMessage, null);
		}
	}

	private Map<String, List<String>> handlePost(HttpExchange he) throws IOException {
		String requestMethod = he.getRequestMethod();
		if (!"POST".equals(requestMethod)) {
			LOGGER.warning("Request method " + requestMethod + " is not allowed for updating.");
			sendErrorResponse(he, 400, "Only POST is allowed for updating.");

			return null;
		}

		URI requestUri = he.getRequestURI();
		System.out.println(requestUri);

		InputStream is = he.getRequestBody();
		Map<String, List<String>> queryMap = null;
		try {
			int bufferSize = 1024;
			char[] buffer = new char[bufferSize];
			StringBuilder out = new StringBuilder();
			Reader in = new InputStreamReader(is, StandardCharsets.UTF_8);
			for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
			     out.append(buffer, 0, numRead);
			}

			LOGGER.finer(out.toString());
			queryMap = querySplit.splitQuery(out.toString());
		}
		catch (IOException e) {
			e.printStackTrace();
			sendErrorResponse(he, 500, "Internal Error.  Check the logs on the host.");

			return null;
		}

		return queryMap;
	}

	private void shortUrls(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.shortUrlDisplayHandler();
			slurpInput(he);

			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void updateShortUrls(HttpExchange he) {
		try {
			String htmlResponse = pasteBinService.updateShortUrlHandler(handlePost(he));

			sendResponseHeadersOK(he);

			OutputStream os = he.getResponseBody();
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write(htmlResponse);
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

}

