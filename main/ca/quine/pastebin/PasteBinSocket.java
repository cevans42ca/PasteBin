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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import java.net.ServerSocket;
import java.net.Socket;

public class PasteBin {

	public static void main(String[] args) throws IOException {
		ServerSocket ss = new ServerSocket(8080);

		while (true) {
			Socket s = ss.accept();
			Thread t = new Thread(() -> { accept(s); });
			t.start();
		}
	}

	private static void accept(Socket s) {
		try {
			InputStream is = s.getInputStream();
			OutputStream os = s.getOutputStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(is));
			String firstLine = null;
			String line = null;
			int blankLines = 0;
			while ((line = br.readLine()) != null) {
				if (firstLine == null) {
					firstLine = line;
				}

				if (line.length() == 0) {
					break;
				}

				System.out.println(line);
			}

			System.out.println("Break!");
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
				bw.write("HTTP/1.0 200 OK");
				bw.write("Content-type: text/html");
				bw.write("");
				bw.write("<html><head><title>PasteBin</title></head>");
				bw.write("<body>");
				bw.write("<form action=\"/paste\">");
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

}
