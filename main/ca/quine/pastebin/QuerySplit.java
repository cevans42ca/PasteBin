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

import java.io.UnsupportedEncodingException;

import java.net.URLDecoder;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public class QuerySplit {

	public QuerySplit() {
		// EMPTY
	}

	public Map<String, List<String>> splitQuery(String queryString) {
		if (queryString == null || queryString.length() == 0) {
			return Collections.emptyMap();
		}

		return Arrays.stream(queryString.split("&")).
				map(this::splitQueryParameter).
				collect(Collectors.groupingBy(SimpleImmutableEntry::getKey,
				LinkedHashMap::new, mapping(Map.Entry::getValue, toList())));
	}

	public SimpleImmutableEntry<String, String> splitQueryParameter(String it)
	{
		final int idx = it.indexOf("=");
		final String key = idx > 0 ? it.substring(0, idx) : it;
		final String value = idx > 0 && it.length() > idx + 1 ? it.substring(idx + 1) : null;
		
		try {
			return new SimpleImmutableEntry<>(
				URLDecoder.decode(key, "UTF-8"),
				URLDecoder.decode(value, "UTF-8")
			);
		}
		catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

}
