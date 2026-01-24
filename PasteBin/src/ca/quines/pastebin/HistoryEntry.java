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

import java.time.Instant;

import java.util.UUID;

/**
 * Represents a single paste entry which could be active, pinned, or deleted.
 * 
 * We use "TS" or "timestamp" for fields and methods to leave it open to change the date/time implementation
 * without changing the fields and methods again.
 */
public class HistoryEntry {

	private Instant createTs;
	private Instant deletedTs;
	private UUID uuid;
	private String text;
	private String shortUrl;

	public HistoryEntry(String text) {
		this(text, Instant.now());
	}

	public HistoryEntry(String text, Instant createInstant) {
		this.text = text;
		this.createTs = createInstant;
		this.uuid = UUID.randomUUID();
	}

	public HistoryEntry(String text, Instant createInstant, Instant deletedInstant, UUID uuid) {
		this.text = text;
		this.createTs = createInstant;
		this.deletedTs = deletedInstant;
		this.uuid = uuid;
	}

	public HistoryEntry(String text, Instant createInstant, Instant deletedInstant, UUID uuid, String shortUrl) {
		this.text = text;
		this.createTs = createInstant;
		this.deletedTs = deletedInstant;
		this.uuid = uuid;
		this.shortUrl = shortUrl;
	}

	public Instant getCreateTs() {
		return createTs;
	}

	public String getText() {
		return text;
	}

	public void setDeletedTs(Instant deletedDate) {
		this.deletedTs = deletedDate;
	}

	public Instant getDeletedTs() {
		return deletedTs;
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
