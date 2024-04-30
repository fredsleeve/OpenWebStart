/* PacFileEvaluator.java
   Copyright (C) 2011 Red Hat, Inc.

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.

IcedTea is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to
the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version.
*/

package com.openwebstart.proxy.pac;

import com.openwebstart.jvm.ui.dialogs.DialogFactory;
import net.adoptopenjdk.icedteaweb.Assert;
import net.adoptopenjdk.icedteaweb.i18n.Translator;
import net.adoptopenjdk.icedteaweb.io.IOUtils;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.adoptopenjdk.icedteaweb.shaded.mozilla.javascript.Context;
import net.adoptopenjdk.icedteaweb.shaded.mozilla.javascript.ContextFactory;
import net.adoptopenjdk.icedteaweb.shaded.mozilla.javascript.Function;
import net.adoptopenjdk.icedteaweb.shaded.mozilla.javascript.Scriptable;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.SocketPermission;
import java.net.URI;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Optional;
import java.util.PropertyPermission;

import static com.openwebstart.proxy.pac.PacConstants.JAVASCRIPT_RUNTIME_PERMISSION_NAME;
import static com.openwebstart.proxy.pac.PacConstants.PAC_HELPER_FUNCTIONS_FILE;
import static com.openwebstart.proxy.pac.PacConstants.PAC_HELPER_FUNCTIONS_INTERNAL_NAME;
import static com.openwebstart.proxy.pac.PacConstants.PAC_METHOD;
import static net.adoptopenjdk.icedteaweb.JavaSystemPropertiesConstants.VM_NAME;
import static sun.security.util.SecurityConstants.PROPERTY_READ_ACTION;

/**
 * Represents a Proxy Auto Config file. This object can be used to evaluate the
 * proxy file to findPreferencesFile the proxy for a given url.
 *
 * @see <a href="http://en.wikipedia.org/wiki/Proxy_auto-config#The_PAC_file">The PAC File</a>
 */
public class PacFileEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(PacFileEvaluator.class);

    private final String pacHelperFunctionContents;
    private final URL pacUrl;

    private final PacProxyCache cache;
    private final ThreadLocal<Boolean> isLoadingPacContent = new ThreadLocal<>();

    private String pacContents;

    /**
     * Initialize a new object by using the PAC file located at the given URL.
     *
     * @param pacUrl the url of the PAC file to use
     */
    public PacFileEvaluator(final URL pacUrl, PacProxyCache cache) throws IOException {
        LOG.debug("Create PAC evaluator for '{}'", pacUrl);
        this.cache = Assert.requireNonNull(cache, "cache");
        this.pacUrl = pacUrl;
        try (final InputStream inputStream = PacFileEvaluator.class.getResourceAsStream(PAC_HELPER_FUNCTIONS_FILE)) {
            this.pacHelperFunctionContents = IOUtils.readContentAsUtf8String(inputStream);
        }
    }

    /**
     * Get the proxies for accessing a given URL. The result is obtained by
     * evaluating the PAC file with the given url (and the host) as input.
     * <p>
     * This method performs caching of the result.
     *
     * @param uri the uri for which a proxy is desired
     * @return a list of proxies in a string like
     * <pre>"PROXY foo.example.com:8080; PROXY bar.example.com:8080; DIRECT"</pre>
     * @see #getProxiesWithoutCaching(URI)
     */
    String getProxies(final URI uri) {
        final String cachedResult = cache.getFromCache(uri);
        if (cachedResult != null) {
            LOG.debug("Cached PAC result for url '{}' -> '{}'", uri, cachedResult);
            return cachedResult;
        }
        // pacUrl.openConnection() must not be called in constructor, otherwise it will initialize HttpURLConnection.userAgent
        // before System.setProperty(HTTP_AGENT, httpAgent) is called in net.sourceforge.jnlp.JNLPFile
        if (pacContents == null) {
            if (isLoadingPacContent.get() != null) {
                // this flag prevents an infinite loop where loadPacContent() triggers a getProxy()
                // this is caused by an issue in the JDK: https://bugs.openjdk.java.net/browse/JDK-8144008
                return null; // null -> NO_PROXY
            } else {
                isLoadingPacContent.set(true);
                pacContents = loadPacContent();
            }
        }
        final String result = getProxiesWithoutCaching(uri);
        LOG.debug("PAC result for url '{}' -> '{}'", uri, result);
        cache.addToCache(uri, result);
        return result;
    }

    private String loadPacContent() {
        LOG.debug("Open PAC url '{}'", pacUrl);
        // need to open PAC URL without Proxy, otherwise it will call getProxies and throw StackOverflowError after a while
        try (final InputStream inputStream = pacUrl.openConnection(Proxy.NO_PROXY).getInputStream()) {
            //PAC supports ASCII and new versions support UTF-8 -> https://en.wikipedia.org/wiki/Proxy_auto-config#PAC_Character-Encoding
            return IOUtils.readContentAsUtf8String(inputStream);
        }
        catch (IOException e) {
            LOG.warn("Cannot open PAC url '{}' due to {}", pacUrl, e.getMessage());
            DialogFactory.showErrorDialog(Translator.getInstance().translate("proxy.error.creationFailed"), e);
            return JNLPRuntime.exit(-1);
        }
    }

    /**
     * Get the proxies for accessing a given URL. The result is obtained by
     * evaluating the PAC file with the given url (and the host) as input.
     *
     * @param uri the url for which a proxy is desired
     * @return a list of proxies in a string like
     * <pre>"PROXY example.com:3128; DIRECT"</pre>
     * @see #getProxies(URI)
     */
    private String getProxiesWithoutCaching(final URI uri) {
        final Permissions p = new Permissions();
        p.add(new RuntimePermission(JAVASCRIPT_RUNTIME_PERMISSION_NAME));
        p.add(new SocketPermission("*", "resolve"));
        p.add(new PropertyPermission(VM_NAME, PROPERTY_READ_ACTION));
        p.add(new PropertyPermission("rhino.stack.style", PROPERTY_READ_ACTION));

        final ProtectionDomain pd = new ProtectionDomain(null, p);
        final AccessControlContext context = new AccessControlContext(new ProtectionDomain[]{pd});

        final PrivilegedAction<String> action = () -> {
            final Context cx = ContextFactory.getGlobal().enterContext();
            try {
                /*
                 * TODO defense in depth.
                 *
                 * This is already running within a sandbox, but we can (and we
                 * should) lock it down further. Look into ClassShutter.
                 */
                final Scriptable scope = cx.initStandardObjects();
                // any optimization level greater than -1 will trigger code generation
                // and this block will then need classloader permissions
                cx.setOptimizationLevel(-1);
                cx.evaluateString(scope, pacHelperFunctionContents, PAC_HELPER_FUNCTIONS_INTERNAL_NAME, 1, null);
                cx.evaluateString(scope, pacContents, pacUrl.toString(), 1, null);

                final Object functionObj = scope.get(PAC_METHOD, scope);
                if (functionObj instanceof Function) {
                    final Object[] args = {uri.toString(), uri.getHost()};
                    final Object result = ((Function) functionObj).call(cx, scope, scope, args);
                    //NULL is valid return value:
                    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_(PAC)_file
                    return Optional.ofNullable(result).map(Object::toString).orElse(null);
                } else {
                    throw new IllegalStateException("'" + PAC_METHOD + "' function not found in pac file");
                }
            } finally {
                Context.exit();
            }
        };
        return AccessController.doPrivileged(action, context);
    }
}
