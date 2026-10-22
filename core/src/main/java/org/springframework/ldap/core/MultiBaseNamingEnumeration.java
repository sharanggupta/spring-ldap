/*
 * Copyright 2006-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ldap.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchResult;

import org.jspecify.annotations.Nullable;

import org.springframework.ldap.support.LdapUtils;
import org.springframework.util.Assert;

/**
 * A {@link NamingEnumeration} over the results of the same search executed against
 * several search bases. All bases are searched up front, in order, because the
 * {@link javax.naming.directory.DirContext} is typically closed before the results are
 * read; the results are then returned one base after the other. An entry that lies under
 * several of the bases is returned once per base.
 * <p>
 * A {@link NamingException} raised by one base, while searching it or while reading its
 * results, is passed to the exception handler. If the handler returns, the base is
 * skipped and the remaining bases are still read; if it throws, nothing more is read.
 *
 * @author Sharang Gupta
 * @since 4.2
 */
final class MultiBaseNamingEnumeration implements NamingEnumeration<SearchResult> {

	private final Iterator<NamingEnumeration<SearchResult>> results;

	private final Consumer<NamingException> exceptionHandler;

	private @Nullable NamingEnumeration<SearchResult> current;

	private boolean pending;

	private MultiBaseNamingEnumeration(List<NamingEnumeration<SearchResult>> results,
			Consumer<NamingException> exceptionHandler) {
		this.results = results.iterator();
		this.exceptionHandler = exceptionHandler;
	}

	/**
	 * Execute the given search against the given bases. The results of a single base are
	 * returned as they are, with any exception propagated to the caller. The results of
	 * several bases are returned one base after the other, in the order of the bases,
	 * with exceptions passed to the given handler.
	 * @param bases the search bases, in the order in which to search them
	 * @param search the search to execute against a base
	 * @param exceptionHandler handles the exception of one base, returning to skip the
	 * base or throwing to give up
	 * @return the results of all bases, in the order of the bases
	 * @throws NamingException if searching a single base fails
	 */
	static NamingEnumeration<SearchResult> search(List<Name> bases, Search search,
			Consumer<NamingException> exceptionHandler) throws NamingException {
		Assert.notEmpty(bases, "bases must not be empty");
		if (bases.size() == 1) {
			return search.apply(bases.get(0));
		}
		List<NamingEnumeration<SearchResult>> results = new ArrayList<>(bases.size());
		try {
			for (Name base : bases) {
				try {
					results.add(search.apply(base));
				}
				catch (NamingException ex) {
					exceptionHandler.accept(ex);
				}
			}
		}
		catch (RuntimeException ex) {
			closeAll(results);
			throw ex;
		}
		return new MultiBaseNamingEnumeration(results, exceptionHandler);
	}

	private static void closeAll(Iterable<NamingEnumeration<SearchResult>> results) {
		for (NamingEnumeration<SearchResult> result : results) {
			closeQuietly(result);
		}
	}

	private static void closeQuietly(NamingEnumeration<SearchResult> result) {
		try {
			result.close();
		}
		catch (NamingException ex) {
			// Never mind this.
		}
	}

	@Override
	public boolean hasMore() {
		if (this.pending) {
			return true;
		}
		while (true) {
			if (this.current != null) {
				boolean more;
				try {
					more = this.current.hasMore();
				}
				catch (NamingException ex) {
					closeQuietly(this.current);
					this.current = null;
					this.exceptionHandler.accept(ex);
					continue;
				}
				if (more) {
					this.pending = true;
					return true;
				}
				closeQuietly(this.current);
				this.current = null;
			}
			if (!this.results.hasNext()) {
				return false;
			}
			this.current = this.results.next();
		}
	}

	@Override
	public SearchResult next() throws NamingException {
		if (!hasMore() || this.current == null) {
			throw new NoSuchElementException();
		}
		this.pending = false;
		return this.current.next();
	}

	@Override
	public boolean hasMoreElements() {
		return hasMore();
	}

	@Override
	public SearchResult nextElement() {
		try {
			return next();
		}
		catch (NamingException ex) {
			throw LdapUtils.convertLdapException(ex);
		}
	}

	@Override
	public void close() {
		try {
			if (this.current != null) {
				closeQuietly(this.current);
			}
		}
		finally {
			this.current = null;
			this.pending = false;
			List<NamingEnumeration<SearchResult>> remaining = new ArrayList<>();
			this.results.forEachRemaining(remaining::add);
			closeAll(remaining);
		}
	}

	/**
	 * A search to execute against one base.
	 */
	interface Search {

		NamingEnumeration<SearchResult> apply(Name base) throws NamingException;

	}

}
