/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.authenticator;

import java.io.IOException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.SessionIdGeneratorBase;
import org.apache.catalina.util.StandardSessionIdGenerator;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.res.StringManager;


/**
 * Basic implementation of the <b>Valve</b> interface that enforces the
 * <code>&lt;security-constraint&gt;</code> elements in the web application
 * deployment descriptor.  This functionality is implemented as a Valve
 * so that it can be omitted in environments that do not require these
 * features.  Individual implementations of each supported authentication
 * method can subclass this base class as required.
 * <p>
 * <b>USAGE CONSTRAINT</b>:  When this class is utilized, the Context to
 * which it is attached (or a parent Container in a hierarchy) must have an
 * associated Realm that can be used for authenticating users and enumerating
 * the roles to which they have been assigned.
 * <p>
 * <b>USAGE CONSTRAINT</b>:  This Valve is only useful when processing HTTP
 * requests.  Requests of any other type will simply be passed through.
 *
 * @author Craig R. McClanahan
 */
public abstract class AuthenticatorBase extends ValveBase
        implements Authenticator {

    private static final Log log = LogFactory.getLog(AuthenticatorBase.class);


    //------------------------------------------------------ Constructor
    public AuthenticatorBase() {
        super(true);
    }

    // ----------------------------------------------------- Instance Variables


    /**
     * Authentication header
     */
    protected static final String AUTH_HEADER_NAME = "WWW-Authenticate";

    /**
     * Default authentication realm name.
     */
    protected static final String REALM_NAME = "Authentication required";

    /**
     * Should a session always be used once a user is authenticated? This may
     * offer some performance benefits since the session can then be used to
     * cache the authenticated Principal, hence removing the need to
     * authenticate the user via the Realm on every request. This may be of help
     * for combinations such as BASIC authentication used with the JNDIRealm or
     * DataSourceRealms. However there will also be the performance cost of
     * creating and GC'ing the session. By default, a session will not be
     * created.
     */
    protected boolean alwaysUseSession = false;


    /**
     * Should we cache authenticated Principals if the request is part of
     * an HTTP session?
     */
    protected boolean cache = true;


    /**
     * Should the session ID, if any, be changed upon a successful
     * authentication to prevent a session fixation attack?
     */
    protected boolean changeSessionIdOnAuthentication = true;

    /**
     * The Context to which this Valve is attached.
     */
    protected Context context = null;


    /**
     * Flag to determine if we disable proxy caching, or leave the issue
     * up to the webapp developer.
     */
    protected boolean disableProxyCaching = true;

    /**
     * Flag to determine if we disable proxy caching with headers incompatible
     * with IE.
     */
    protected boolean securePagesWithPragma = false;

    /**
     * The Java class name of the secure random number generator class to be
     * used when generating SSO session identifiers. The random number generator
     * class must be self-seeding and have a zero-argument constructor. If not
     * specified, an instance of {@link java.security.SecureRandom} will be
     * generated.
     */
    protected String secureRandomClass = null;

    /**
     * The name of the algorithm to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate SSO session
     * IDs. If no algorithm is specified, SHA1PRNG is used. To use the platform
     * default (which may be SHA1PRNG), specify the empty string. If an invalid
     * algorithm and/or provider is specified the SecureRandom instances will be
     * created using the defaults. If that fails, the SecureRandom instances
     * will be created using platform defaults.
     */
    protected String secureRandomAlgorithm = "SHA1PRNG";

    /**
     * The name of the provider to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate session SSO
     * IDs. If no algorithm is specified the of SHA1PRNG default is used. If an
     * invalid algorithm and/or provider is specified the SecureRandom instances
     * will be created using the defaults. If that fails, the SecureRandom
     * instances will be created using platform defaults.
     */
    protected String secureRandomProvider = null;

    protected SessionIdGeneratorBase sessionIdGenerator = null;

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);


    /**
     * The SingleSignOn implementation in our request processing chain,
     * if there is one.
     */
    protected SingleSignOn sso = null;


    /**
     * "Expires" header always set to Date(1), so generate once only
     */
    private static final String DATE_ONE =
        (new SimpleDateFormat(FastHttpDateFormat.RFC1123_DATE,
                              Locale.US)).format(new Date(1));


    protected static String getRealmName(Context context) {
        if (context == null) {
            // Very unlikely
            return REALM_NAME;
        }

        LoginConfig config = context.getLoginConfig();
        if (config == null) {
            return REALM_NAME;
        }

        String result = config.getRealmName();
        if (result == null) {
            return REALM_NAME;
        }

        return result;
    }


    // ------------------------------------------------------------- Properties


    public boolean getAlwaysUseSession() {
        return alwaysUseSession;
    }


    public void setAlwaysUseSession(boolean alwaysUseSession) {
        this.alwaysUseSession = alwaysUseSession;
    }


    /**
     * Return the cache authenticated Principals flag.
     */
    public boolean getCache() {

        return (this.cache);

    }


    /**
     * Set the cache authenticated Principals flag.
     *
     * @param cache The new cache flag
     */
    public void setCache(boolean cache) {

        this.cache = cache;

    }


    /**
     * Return the Container to which this Valve is attached.
     */
    @Override
    public Container getContainer() {

        return (this.context);

    }


    /**
     * Set the Container to which this Valve is attached.
     *
     * @param container The container to which we are attached
     */
    @Override
    public void setContainer(Container container) {

        if (container != null && !(container instanceof Context)) {
            throw new IllegalArgumentException
                (sm.getString("authenticator.notContext"));
        }

        super.setContainer(container);
        this.context = (Context) container;

    }


    /**
     * Return the flag that states if we add headers to disable caching by
     * proxies.
     */
    public boolean getDisableProxyCaching() {
        return disableProxyCaching;
    }

    /**
     * Set the value of the flag that states if we add headers to disable
     * caching by proxies.
     * @param nocache <code>true</code> if we add headers to disable proxy
     *              caching, <code>false</code> if we leave the headers alone.
     */
    public void setDisableProxyCaching(boolean nocache) {
        disableProxyCaching = nocache;
    }

    /**
     * Return the flag that states, if proxy caching is disabled, what headers
     * we add to disable the caching.
     */
    public boolean getSecurePagesWithPragma() {
        return securePagesWithPragma;
    }

    /**
     * Set the value of the flag that states what headers we add to disable
     * proxy caching.
     * @param securePagesWithPragma <code>true</code> if we add headers which
     * are incompatible with downloading office documents in IE under SSL but
     * which fix a caching problem in Mozilla.
     */
    public void setSecurePagesWithPragma(boolean securePagesWithPragma) {
        this.securePagesWithPragma = securePagesWithPragma;
    }

    /**
     * Return the flag that states if we should change the session ID of an
     * existing session upon successful authentication.
     *
     * @return <code>true</code> to change session ID upon successful
     *         authentication, <code>false</code> to do not perform the change.
     */
    public boolean getChangeSessionIdOnAuthentication() {
        return changeSessionIdOnAuthentication;
    }

    /**
     * Set the value of the flag that states if we should change the session ID
     * of an existing session upon successful authentication.
     *
     * @param changeSessionIdOnAuthentication
     *            <code>true</code> to change session ID upon successful
     *            authentication, <code>false</code> to do not perform the
     *            change.
     */
    public void setChangeSessionIdOnAuthentication(
            boolean changeSessionIdOnAuthentication) {
        this.changeSessionIdOnAuthentication = changeSessionIdOnAuthentication;
    }

    /**
     * Return the secure random number generator class name.
     */
    public String getSecureRandomClass() {

        return (this.secureRandomClass);

    }


    /**
     * Set the secure random number generator class name.
     *
     * @param secureRandomClass The new secure random number generator class
     *                          name
     */
    public void setSecureRandomClass(String secureRandomClass) {
        this.secureRandomClass = secureRandomClass;
    }


    /**
     * Return the secure random number generator algorithm name.
     */
    public String getSecureRandomAlgorithm() {
        return secureRandomAlgorithm;
    }


    /**
     * Set the secure random number generator algorithm name.
     *
     * @param secureRandomAlgorithm The new secure random number generator
     *                              algorithm name
     */
    public void setSecureRandomAlgorithm(String secureRandomAlgorithm) {
        this.secureRandomAlgorithm = secureRandomAlgorithm;
    }


    /**
     * Return the secure random number generator provider name.
     */
    public String getSecureRandomProvider() {
        return secureRandomProvider;
    }


    /**
     * Set the secure random number generator provider name.
     *
     * @param secureRandomProvider The new secure random number generator
     *                             provider name
     */
    public void setSecureRandomProvider(String secureRandomProvider) {
        this.secureRandomProvider = secureRandomProvider;
    }



    // --------------------------------------------------------- Public Methods


    /**
     * Enforce the security restrictions in the web application deployment
     * descriptor of our associated Context.
     *
     * @param request Request to be processed
     * @param response Response to be processed
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if thrown by a processing element
     */
    @Override
    public void invoke(Request request, Response response)
        throws IOException, ServletException {

        if (log.isDebugEnabled()) {
            log.debug("Security checking request " +
                request.getMethod() + " " + request.getRequestURI());
        }

        // Have we got a cached authenticated Principal to record?
        if (cache) {
            Principal principal = request.getUserPrincipal();
            if (principal == null) {
                Session session = request.getSessionInternal(false);
                if (session != null) {
                    principal = session.getPrincipal();
                    if (principal != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("We have cached auth type " +
                                session.getAuthType() +
                                " for principal " +
                                session.getPrincipal());
                        }
                        request.setAuthType(session.getAuthType());
                        request.setUserPrincipal(principal);
                    }
                }
            }
        }

        // Special handling for form-based logins to deal with the case
        // where the login form (and therefore the "j_security_check" URI
        // to which it submits) might be outside the secured area
        String contextPath = this.context.getPath();
        String requestURI = request.getDecodedRequestURI();
        if (requestURI.startsWith(contextPath) &&
            requestURI.endsWith(Constants.FORM_ACTION)) {
            if (!authenticate(request, response)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed authenticate() test ??" + requestURI );
                }
                return;
            }
        }

        // Special handling for form-based logins to deal with the case where
        // a resource is protected for some HTTP methods but not protected for
        // GET which is used after authentication when redirecting to the
        // protected resource.
        // TODO: This is similar to the FormAuthenticator.matchRequest() logic
        //       Is there a way to remove the duplication?
        Session session = request.getSessionInternal(false);
        if (session != null) {
            SavedRequest savedRequest =
                    (SavedRequest) session.getNote(Constants.FORM_REQUEST_NOTE);
            if (savedRequest != null) {
                String decodedRequestURI = request.getDecodedRequestURI();
                if (decodedRequestURI != null &&
                        decodedRequestURI.equals(
                                savedRequest.getDecodedRequestURI())) {
                    if (!authenticate(request, response)) {
                        if (log.isDebugEnabled()) {
                            log.debug(" Failed authenticate() test");
                        }
                        /*
                         * ASSERT: Authenticator already set the appropriate
                         * HTTP status code, so we do not have to do anything
                         * special
                         */
                        return;
                    }
                }
            }
        }

        // The Servlet may specify security constraints through annotations.
        // Ensure that they have been processed before constraints are checked
        Wrapper wrapper = request.getMappingData().wrapper;
        if (wrapper != null) {
            wrapper.servletSecurityAnnotationScan();
        }

        Realm realm = this.context.getRealm();
        // Is this request URI subject to a security constraint?
        SecurityConstraint [] constraints
            = realm.findSecurityConstraints(request, this.context);

        if (constraints == null && !context.getPreemptiveAuthentication()) {
            if (log.isDebugEnabled()) {
                log.debug(" Not subject to any constraint");
            }
            getNext().invoke(request, response);
            return;
        }

        // Make sure that constrained resources are not cached by web proxies
        // or browsers as caching can provide a security hole
        if (constraints != null && disableProxyCaching &&
            !"POST".equalsIgnoreCase(request.getMethod())) {
            if (securePagesWithPragma) {
                // Note: These can cause problems with downloading files with IE
                response.setHeader("Pragma", "No-cache");
                response.setHeader("Cache-Control", "no-cache");
            } else {
                response.setHeader("Cache-Control", "private");
            }
            response.setHeader("Expires", DATE_ONE);
        }

        int i;
        if (constraints != null) {
            // Enforce any user data constraint for this security constraint
            if (log.isDebugEnabled()) {
                log.debug(" Calling hasUserDataPermission()");
            }
            if (!realm.hasUserDataPermission(request, response,
                                             constraints)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed hasUserDataPermission() test");
                }
                /*
                 * ASSERT: Authenticator already set the appropriate
                 * HTTP status code, so we do not have to do anything special
                 */
                return;
            }
        }

        // Since authenticate modifies the response on failure,
        // we have to check for allow-from-all first.
        boolean authRequired;
        if (constraints == null) {
            authRequired = false;
        } else {
            authRequired = true;
            for(i=0; i < constraints.length && authRequired; i++) {
                if(!constraints[i].getAuthConstraint()) {
                    authRequired = false;
                    break;
                } else if(!constraints[i].getAllRoles() &&
                        !constraints[i].getAuthenticatedUsers()) {
                    String [] roles = constraints[i].findAuthRoles();
                    if(roles == null || roles.length == 0) {
                        authRequired = false;
                        break;
                    }
                }
            }
        }

        if (!authRequired && context.getPreemptiveAuthentication()) {
            authRequired =
                request.getCoyoteRequest().getMimeHeaders().getValue(
                        "authorization") != null;
        }

        if (!authRequired && context.getPreemptiveAuthentication()) {
            X509Certificate[] certs = getRequestCertificates(request, false);
            authRequired = certs != null && certs.length > 0;
        }

        if(authRequired) {
            if (log.isDebugEnabled()) {
                log.debug(" Calling authenticate()");
            }
            if (!authenticate(request, response)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed authenticate() test");
                }
                /*
                 * ASSERT: Authenticator already set the appropriate
                 * HTTP status code, so we do not have to do anything
                 * special
                 */
                return;
            }

        }

        if (constraints != null) {
            if (log.isDebugEnabled()) {
                log.debug(" Calling accessControl()");
            }
            if (!realm.hasResourcePermission(request, response,
                                             constraints,
                                             this.context)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed accessControl() test");
                }
                /*
                 * ASSERT: AccessControl method has already set the
                 * appropriate HTTP status code, so we do not have to do
                 * anything special
                 */
                return;
            }
        }

        // Any and all specified constraints have been satisfied
        if (log.isDebugEnabled()) {
            log.debug(" Successfully passed all security constraints");
        }
        getNext().invoke(request, response);

    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Look for the X509 certificate chain in the Request under the key
     * <code>javax.servlet.request.X509Certificate</code>. If not found, trigger
     * extracting the certificate chain from the Coyote request.
     *
     * @param request   Request to be processed
     * @param force     Should a renegotiation be forced to request certificates
     *                  from the user agent if none have been provided
     *
     * @return          The X509 certificate chain if found, <code>null</code>
     *                  otherwise.
     */
    protected X509Certificate[] getRequestCertificates(final Request request, boolean force)
            throws IllegalStateException {

        X509Certificate certs[] =
                (X509Certificate[]) request.getAttribute(Globals.CERTIFICATES_ATTR);

        if ((certs == null) || (certs.length < 1)) {
            try {
                request.getCoyoteRequest().action(ActionCode.REQ_SSL_CERTIFICATE, Boolean.valueOf(force));
                certs = (X509Certificate[]) request.getAttribute(Globals.CERTIFICATES_ATTR);
            } catch (IllegalStateException ise) {
                // Request body was too large for save buffer
                // Return null which will trigger an auth failure
            }
        }

        return certs;
    }


    /**
     * Associate the specified single sign on identifier with the
     * specified Session.
     *
     * @param ssoId Single sign on identifier
     * @param session Session to be associated
     */
    protected void associate(String ssoId, Session session) {

        if (sso == null) {
            return;
        }
        sso.associate(ssoId, session);

    }


    /**
     * Authenticate the user making this request, based on the login
     * configuration of the {@link Context} with which this Authenticator is
     * associated.  Return <code>true</code> if any specified constraint has
     * been satisfied, or <code>false</code> if we have created a response
     * challenge already.
     *
     * @param request Request we are processing
     * @param response Response we are populating
     *
     * @exception IOException if an input/output error occurs
     */
    @Override
    public abstract boolean authenticate(Request request,
            HttpServletResponse response) throws IOException;


    /**
     * Attempts reauthentication to the <code>Realm</code> using
     * the credentials included in argument <code>entry</code>.
     *
     * @param ssoId identifier of SingleSignOn session with which the
     *              caller is associated
     * @param request   the request that needs to be authenticated
     */
    protected boolean reauthenticateFromSSO(String ssoId, Request request) {

        if (sso == null || ssoId == null) {
            return false;
        }

        boolean reauthenticated = false;

        Container parent = getContainer();
        if (parent != null) {
            Realm realm = parent.getRealm();
            if (realm != null) {
                reauthenticated = sso.reauthenticate(ssoId, realm, request);
            }
        }

        if (reauthenticated) {
            associate(ssoId, request.getSessionInternal(true));

            if (log.isDebugEnabled()) {
                log.debug(" Reauthenticated cached principal '" +
                          request.getUserPrincipal().getName() +
                          "' with auth type '" +  request.getAuthType() + "'");
            }
        }

        return reauthenticated;
    }


    /**
     * Register an authenticated Principal and authentication type in our
     * request, in the current session (if there is one), and with our
     * SingleSignOn valve, if there is one.  Set the appropriate cookie
     * to be returned.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are generating
     * @param principal The authenticated Principal to be registered
     * @param authType The authentication type to be registered
     * @param username Username used to authenticate (if any)
     * @param password Password used to authenticate (if any)
     */
    public void register(Request request, HttpServletResponse response,
                            Principal principal, String authType,
                            String username, String password) {

        if (log.isDebugEnabled()) {
            String name = (principal == null) ? "none" : principal.getName();
            log.debug("Authenticated '" + name + "' with type '" + authType +
                    "'");
        }

        // Cache the authentication information in our request
        request.setAuthType(authType);
        request.setUserPrincipal(principal);

        Session session = request.getSessionInternal(false);

        if (session != null) {
            if (changeSessionIdOnAuthentication) {
                String oldId = null;
                if (log.isDebugEnabled()) {
                    oldId = session.getId();
                }
                Manager manager = request.getContext().getManager();
                manager.changeSessionId(session);
                request.changeSessionId(session.getId());
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("authenticator.changeSessionId",
                            oldId, session.getId()));
                }
            }
        } else if (alwaysUseSession) {
            session = request.getSessionInternal(true);
        }

        // Cache the authentication information in our session, if any
        if (cache) {
            if (session != null) {
                session.setAuthType(authType);
                session.setPrincipal(principal);
                if (username != null) {
                    session.setNote(Constants.SESS_USERNAME_NOTE, username);
                } else {
                    session.removeNote(Constants.SESS_USERNAME_NOTE);
                }
                if (password != null) {
                    session.setNote(Constants.SESS_PASSWORD_NOTE, password);
                } else {
                    session.removeNote(Constants.SESS_PASSWORD_NOTE);
                }
            }
        }

        // Construct a cookie to be returned to the client
        if (sso == null) {
            return;
        }

        // Only create a new SSO entry if the SSO did not already set a note
        // for an existing entry (as it would do with subsequent requests
        // for DIGEST and SSL authenticated contexts)
        String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
        if (ssoId == null) {
            // Construct a cookie to be returned to the client
            ssoId = sessionIdGenerator.generateSessionId();
            Cookie cookie = new Cookie(Constants.SINGLE_SIGN_ON_COOKIE, ssoId);
            cookie.setMaxAge(-1);
            cookie.setPath("/");

            // Bugzilla 41217
            cookie.setSecure(request.isSecure());

            // Bugzilla 34724
            String ssoDomain = sso.getCookieDomain();
            if(ssoDomain != null) {
                cookie.setDomain(ssoDomain);
            }

            // Configure httpOnly on SSO cookie using same rules as session cookies
            if (request.getServletContext().getSessionCookieConfig().isHttpOnly() ||
                    request.getContext().getUseHttpOnly()) {
                cookie.setHttpOnly(true);
            }

            response.addCookie(cookie);

            // Register this principal with our SSO valve
            sso.register(ssoId, principal, authType, username, password);
            request.setNote(Constants.REQ_SSOID_NOTE, ssoId);

        } else {
            if (principal == null) {
                // Registering a programmatic logout
                sso.deregister(ssoId);
                request.removeNote(Constants.REQ_SSOID_NOTE);
                return;
            } else {
                // Update the SSO session with the latest authentication data
                sso.update(ssoId, principal, authType, username, password);
            }
        }

        // Fix for Bug 10040
        // Always associate a session with a new SSO reqistration.
        // SSO entries are only removed from the SSO registry map when
        // associated sessions are destroyed; if a new SSO entry is created
        // above for this request and the user never revisits the context, the
        // SSO entry will never be cleared if we don't associate the session
        if (session == null) {
            session = request.getSessionInternal(true);
        }
        sso.associate(ssoId, session);

    }

    @Override
    public void login(String username, String password, Request request)
            throws ServletException {
        Principal principal = doLogin(request, username, password);
        register(request, request.getResponse(), principal,
                    getAuthMethod(), username, password);
    }

    protected abstract String getAuthMethod();

    /**
     * Process the login request.
     *
     * @param request   Associated request
     * @param username  The user
     * @param password  The password
     * @return          The authenticated Principal
     * @throws ServletException
     */
    protected Principal doLogin(Request request, String username,
            String password) throws ServletException {
        Principal p = context.getRealm().authenticate(username, password);
        if (p == null) {
            throw new ServletException(sm.getString("authenticator.loginFail"));
        }
        return p;
    }

    @Override
    public void logout(Request request) {
        register(request, request.getResponse(), null,
                null, null, null);

    }

    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Look up the SingleSignOn implementation in our request processing
        // path, if there is one
        Container parent = context.getParent();
        while ((sso == null) && (parent != null)) {
            Valve valves[] = parent.getPipeline().getValves();
            for (int i = 0; i < valves.length; i++) {
                if (valves[i] instanceof SingleSignOn) {
                    sso = (SingleSignOn) valves[i];
                    break;
                }
            }
            if (sso == null) {
                parent = parent.getParent();
            }
        }
        if (log.isDebugEnabled()) {
            if (sso != null) {
                log.debug("Found SingleSignOn Valve at " + sso);
            } else {
                log.debug("No SingleSignOn Valve is present");
            }
        }

        sessionIdGenerator = new StandardSessionIdGenerator();
        sessionIdGenerator.setSecureRandomAlgorithm(getSecureRandomAlgorithm());
        sessionIdGenerator.setSecureRandomClass(getSecureRandomClass());
        sessionIdGenerator.setSecureRandomProvider(getSecureRandomProvider());

        super.startInternal();
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        super.stopInternal();

        sso = null;
    }
}
