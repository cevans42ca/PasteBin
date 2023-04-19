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

import java.util.Date;
import java.util.UUID;

public class HistoryEntry {

	private Date createDate;
	private Date deletedDate;
	private UUID uuid;
	private String text;
	private String shortUrl;

	public HistoryEntry(String text) {
		this(text, new Date());
	}

	public HistoryEntry(String text, Date createDate) {
		this.text = text;
		this.createDate = createDate;
		this.uuid = UUID.randomUUID();
	}

	public HistoryEntry(String text, Date createDate, Date deletedDate, UUID uuid) {
		this.text = text;
		this.createDate = createDate;
		this.deletedDate = deletedDate;
		this.uuid = uuid;
	}

	public HistoryEntry(String text, Date createDate, Date deletedDate, UUID uuid, String shortUrl) {
		this.text = text;
		this.createDate = createDate;
		this.deletedDate = deletedDate;
		this.uuid = uuid;
		this.shortUrl = shortUrl;
	}

	public Date getCreateDate() {
		return createDate;
	}

	public String getText() {
		return text;
	}

	public void setDeletedDate(Date deletedDate) {
		this.deletedDate = deletedDate;
	}

	public Date getDeletedDate() {
		return deletedDate;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setShortUrl(String shortUrl) {
		this.shortUrl = shortUrl;
	}

	public String getShortUrl() {
		return shortUrl;
	}

}
