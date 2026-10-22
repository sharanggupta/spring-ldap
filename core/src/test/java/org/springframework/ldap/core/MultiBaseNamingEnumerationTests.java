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

import java.util.List;
import java.util.function.Consumer;

import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchResult;

import org.junit.jupiter.api.Test;

import org.springframework.ldap.UncategorizedLdapException;
import org.springframework.ldap.support.LdapUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link MultiBaseNamingEnumeration}.
 *
 * @author Sharang Gupta
 */
public class MultiBaseNamingEnumerationTests {

	private static final Name USERS = LdapUtils.newLdapName("ou=users");

	private static final Name SERVICE_ACCOUNTS = LdapUtils.newLdapName("ou=sa");

	private static final Consumer<NamingException> IGNORE = (ex) -> {
	};

	private static final Consumer<NamingException> RETHROW = (ex) -> {
		throw LdapUtils.convertLdapException(ex);
	};

	private final NamingEnumeration<SearchResult> usersResults = mock(NamingEnumeration.class);

	private final NamingEnumeration<SearchResult> serviceAccountsResults = mock(NamingEnumeration.class);

	private final MultiBaseNamingEnumeration.Search search = mock(MultiBaseNamingEnumeration.Search.class);

	private final SearchResult one = new SearchResult("cn=one", null, new BasicAttributes());

	private final SearchResult two = new SearchResult("cn=two", null, new BasicAttributes());

	@Test
	public void singleBaseReturnsItsResultsAsTheyAre() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS), this.search,
				RETHROW);

		assertThat(results).isSameAs(this.usersResults);
	}

	@Test
	public void resultsFollowTheOrderOfTheBases() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);
		given(this.search.apply(SERVICE_ACCOUNTS)).willReturn(this.serviceAccountsResults);
		given(this.usersResults.hasMore()).willReturn(true, false);
		given(this.usersResults.next()).willReturn(this.one);
		given(this.serviceAccountsResults.hasMore()).willReturn(true, false);
		given(this.serviceAccountsResults.next()).willReturn(this.two);

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS),
				this.search, RETHROW);

		assertThat(results.hasMore()).isTrue();
		assertThat(results.hasMore()).isTrue();
		assertThat(results.next()).isSameAs(this.one);
		assertThat(results.next()).isSameAs(this.two);
		assertThat(results.hasMore()).isFalse();
		verify(this.usersResults).close();
		verify(this.serviceAccountsResults).close();
	}

	@Test
	public void ignoredExceptionWhileReadingABaseContinuesWithTheNextBase() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);
		given(this.search.apply(SERVICE_ACCOUNTS)).willReturn(this.serviceAccountsResults);
		given(this.usersResults.hasMore()).willReturn(true).willThrow(new SizeLimitExceededException());
		given(this.usersResults.next()).willReturn(this.one);
		given(this.serviceAccountsResults.hasMore()).willReturn(true, false);
		given(this.serviceAccountsResults.next()).willReturn(this.two);

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS),
				this.search, IGNORE);

		assertThat(results.next()).isSameAs(this.one);
		assertThat(results.next()).isSameAs(this.two);
		assertThat(results.hasMore()).isFalse();
		verify(this.usersResults).close();
	}

	@Test
	public void exceptionWhileReadingABaseStopsWhenNotIgnored() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);
		given(this.search.apply(SERVICE_ACCOUNTS)).willReturn(this.serviceAccountsResults);
		given(this.usersResults.hasMore()).willThrow(new SizeLimitExceededException());

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS),
				this.search, RETHROW);

		assertThatExceptionOfType(org.springframework.ldap.SizeLimitExceededException.class)
			.isThrownBy(results::hasMore);
		verify(this.usersResults).close();
		verify(this.serviceAccountsResults, never()).hasMore();
	}

	@Test
	public void ignoredExceptionWhileSearchingABaseSkipsIt() throws Exception {
		given(this.search.apply(USERS)).willThrow(new NameNotFoundException());
		given(this.search.apply(SERVICE_ACCOUNTS)).willReturn(this.serviceAccountsResults);
		given(this.serviceAccountsResults.hasMore()).willReturn(true, false);
		given(this.serviceAccountsResults.next()).willReturn(this.two);

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS),
				this.search, IGNORE);

		assertThat(results.next()).isSameAs(this.two);
		assertThat(results.hasMore()).isFalse();
	}

	@Test
	public void exceptionWhileSearchingALaterBaseClosesTheEarlierResults() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);
		given(this.search.apply(SERVICE_ACCOUNTS)).willThrow(new NamingException("failed"));

		assertThatExceptionOfType(UncategorizedLdapException.class).isThrownBy(
				() -> MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS), this.search, RETHROW));
		verify(this.usersResults).close();
	}

	@Test
	public void closeClosesTheCurrentAndTheRemainingResults() throws Exception {
		given(this.search.apply(USERS)).willReturn(this.usersResults);
		given(this.search.apply(SERVICE_ACCOUNTS)).willReturn(this.serviceAccountsResults);
		given(this.usersResults.hasMore()).willReturn(true);
		given(this.usersResults.next()).willReturn(this.one);

		NamingEnumeration<SearchResult> results = MultiBaseNamingEnumeration.search(List.of(USERS, SERVICE_ACCOUNTS),
				this.search, RETHROW);
		assertThat(results.next()).isSameAs(this.one);
		results.close();

		verify(this.usersResults).close();
		verify(this.serviceAccountsResults).close();
		verify(this.serviceAccountsResults, never()).hasMore();
		assertThat(results.hasMore()).isFalse();
	}

	@Test
	public void noBasesIsRejected() throws Exception {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> MultiBaseNamingEnumeration.search(List.of(), this.search, RETHROW));
		verify(this.search, never()).apply(any());
	}

}
