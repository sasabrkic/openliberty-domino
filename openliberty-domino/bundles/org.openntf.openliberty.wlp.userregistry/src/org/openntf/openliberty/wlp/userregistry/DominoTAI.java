/**
 * Copyright © 2018-2019 Jesse Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.openliberty.wlp.userregistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ibm.websphere.security.WebTrustAssociationException;
import com.ibm.websphere.security.WebTrustAssociationFailedException;
import com.ibm.wsspi.security.tai.TAIResult;
import com.ibm.wsspi.security.tai.TrustAssociationInterceptor;

public class DominoTAI implements TrustAssociationInterceptor {
	private static final Logger log = Logger.getLogger(DominoTAI.class.getPackage().getName());
	static {
		log.setLevel(Level.FINER);
	}
	
	private static final String ENV_PROXY = System.getenv("Domino_HTTP");
	private static final boolean enabled = ENV_PROXY != null && !ENV_PROXY.isEmpty();
	
	// TODO make this customizable
	private static final Collection<String> COOKIES = Arrays.asList("DomAuthSessId", "LtpaToken", "LtpaToken2");
	
	public DominoTAI() {
	}

	@Override
	public int initialize(Properties props) throws WebTrustAssociationFailedException {
		if(log.isLoggable(Level.FINER)) {
			log.finer(getClass().getSimpleName() + ": Enabled? " + enabled);
		}
		return 0;
	}
	
	@Override
	public void cleanup() {
		// NOP
	}

	@Override
	public String getType() {
		return getClass().getName();
	}

	@Override
	public String getVersion() {
		return "1.0";
	}

	@Override
	public boolean isTargetInterceptor(HttpServletRequest req) throws WebTrustAssociationException {
		if(!enabled) {
			return false;
		}
		
		Cookie[] cookies = req.getCookies();
		if(cookies == null || cookies.length == 0) {
			return false;
		}
		
		if(Arrays.stream(cookies).map(Cookie::getName).anyMatch(COOKIES::contains)) {
			if(log.isLoggable(Level.FINE)) {
				log.fine(getClass().getSimpleName() + ": Found a matching request");
			}
			return true;
		}
		
		if(log.isLoggable(Level.FINER)) {
			log.finer(getClass().getSimpleName() + ": Skipping non-matching request");
		}
		return false;
	}

	@Override
	public TAIResult negotiateValidateandEstablishTrust(HttpServletRequest req, HttpServletResponse resp)
			throws WebTrustAssociationFailedException {
		// We must have a match - check against the Domino server
		try {
			URL url = new URL(ENV_PROXY);
			url = new URL(url, "/org.openntf.openliberty.domino/whoami");
			HttpURLConnection conn = (HttpURLConnection)url.openConnection();
			try {
				conn.setRequestMethod("GET");
				conn.setRequestProperty("Host", url.getHost());
				
				if(req.getHeader("Cookie") != null) {
					conn.setRequestProperty("Cookie", req.getHeader("Cookie"));
				}
				if(req.getHeader("Authorization") != null) {
					conn.setRequestProperty("Authorization", req.getHeader("Authorization"));
				}
				conn.connect();
				String name;
				try(InputStream is = conn.getInputStream()) {
					try(BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
						name = r.readLine();
					}
				}
				if(log.isLoggable(Level.FINE)) {
					log.fine(getClass().getSimpleName() + ": Resolved to user name " + name);
				}
				if("Anonymous".equals(name)) {
					name = "anonymous";
				}
				return TAIResult.create(HttpServletResponse.SC_OK, name);
			} finally {
				conn.disconnect();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
