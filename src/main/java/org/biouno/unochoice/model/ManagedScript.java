/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2020 Ioannis Moutsatsos, Bruno P. Kinoshita
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.biouno.unochoice.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ManagementLink;
import hudson.model.Project;
import hudson.model.Queue;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.biouno.unochoice.Messages;
import org.biouno.unochoice.util.Utils;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType.DefinedType;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.jenkinsci.plugins.configfiles.ConfigFilesManagement;
import org.jenkinsci.plugins.configfiles.groovy.GroovyScript.GroovyConfigProvider;
import org.jenkinsci.plugins.configfiles.utils.DescriptionResponse;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A managed groovy script.
 *
 * @author Chris Foote
 * @since 0.1
 */
public class ManagedScript extends AbstractScript {

    private static final Logger LOGGER = Logger.getLogger(ManagedScript.class.getName());

    /*
     * Serial UID.
     */
    private static final long serialVersionUID = -6600327523009436354L;

    private final String scriptId;

    // Map is not serializable, but LinkedHashMap is. Ignore static analysis errors
    private final Map<String, String> args;

    public static class ArgValue implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final String value;

        @DataBoundConstructor
        public ArgValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    @DataBoundConstructor
    public ManagedScript(String scriptId, List<ArgValue> args) {
        super();
        this.scriptId = scriptId;
        this.args = new LinkedHashMap<>();
        if (args != null) {
            for (ArgValue arg : args) {
                this.args.put(arg.name, arg.value);
            }
        }
    }

    /**
     * @return the scriptId
     */
    public String getScriptId() {
        return scriptId;
    }

    /**
     * @return the args
     */
    public Map<String, String> getArgs() {
        return args;
    }

    @Override
    public Object eval() {
        return eval(null);
    }

    /*
     * (non-Javadoc)
     * @see org.biouno.unochoice.model.Script#eval(java.util.Map)
     */
    @Override
    public Object eval(Map<String, String> args) {
        final Map<String, String> envVars = Utils.getSystemEnv();
        Map<String, String> evaledArgs = new LinkedHashMap<>(envVars);
        // if we have any args that came from UI, let's eval and use them
        if (args != null && !args.isEmpty()) {
            // fill our map with the given parameters
            evaledArgs.putAll(args);
            // and now try to expand env vars
            for (String key : this.getArgs().keySet()) {
                String value = this.getArgs().get(key);
                value = Util.replaceMacro(value, args);
                evaledArgs.put(key, value);
            }
        } else {
            evaledArgs.putAll(this.getArgs());
        }

        final Jenkins instance = Jenkins.getInstanceOrNull();
        return this.toGroovyScript(instance).eval(evaledArgs);
    }

    // --- utility methods for conversion

    /**
     * Converts this script to a GroovyScript.
     *
     * @return a GroovyScript
     */
    public GroovyScript toGroovyScript(ItemGroup itemGroup) {
        final Config groovyConfig = ConfigFiles.getByIdOrNull(itemGroup, getScriptId());
        if (groovyConfig == null) {
            throw new IllegalStateException(Messages.config_does_not_exist(getScriptId()));
        }
        return new GroovyScript(new SecureGroovyScript(groovyConfig.content, true, null), null);
    }

    // --- descriptor

    @Extension(optional = true)
    public static class DescriptorImpl extends ScriptDescriptor {
        static {
            // make sure this class fails to load during extension discovery if config-file-provider isn't present
            ConfigFiles.getConfigsInContext(null, GroovyConfigProvider.class);
        }
        /*
         * (non-Javadoc)
         *
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName() {
            return "Managed Script";
        }

        @Override
        public AbstractScript newInstance(StaplerRequest req, JSONObject jsonObject) throws FormException {
            ManagedScript script = null;
            String scriptId = jsonObject.getString("scriptId");
            if (scriptId != null && !scriptId.trim().equals("")) {
                List<ManagedScript.ArgValue> args = new ArrayList<>();

                final JSONObject defineArgs = jsonObject.getJSONObject("defineArgs");
                if (defineArgs != null && !defineArgs.isNullObject()) {
                    JSONObject argsObj = defineArgs.optJSONObject("args");
                    if (argsObj == null) {
                        JSONArray argsArrayObj = defineArgs.optJSONArray("args");
                        if (argsArrayObj != null) {
                            for (int i = 0; i < argsArrayObj.size(); i++) {
                                JSONObject obj = argsArrayObj.getJSONObject(i);
                                String name = obj.getString("name");
                                String value = obj.getString("value");
                                if (name != null && !name.trim().equals("") && value != null) {
                                    ManagedScript.ArgValue arg = new ManagedScript.ArgValue(name, value);
                                    args.add(arg);
                                }
                            }
                        }
                    } else {
                        String name = argsObj.getString("name");
                        String value = argsObj.getString("value");
                        if (name != null && !name.trim().equals("") && value != null) {
                            ManagedScript.ArgValue arg = new ManagedScript.ArgValue(name, value);
                            args.add(arg);
                        }
                    }
                }
                script = new ManagedScript(scriptId, args);
            }
            return script;
        }

        @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
        private ManagementLink getConfigFilesManagement() {
            return Jenkins.get().getExtensionList(ConfigFilesManagement.class).get(0);
        }

        /**
         * Return all groovy scripts that the user can choose from when adding a parameter. Ordered by name.
         *
         * @return A collection of groovy scripts.
         */
        public ListBoxModel doFillScriptIdItems(@AncestorInPath ItemGroup context) {
            List<Config> groovyScripts = new ArrayList<>();
            List<Config> configsInContext = ConfigFiles.getConfigsInContext(context, null);
            for (Config cfg : configsInContext) {
                ConfigProvider provider = cfg.getProvider();
                if (provider.getContentType().equals(DefinedType.GROOVY)) {
                    groovyScripts.add(cfg);
                }
            }

            Collections.sort(groovyScripts, new Comparator<Config>() {
                public int compare(Config o1, Config o2) {
                    return o1.name.compareTo(o2.name);
                }
            });
            ListBoxModel items = new ListBoxModel();
            items.add("please select", "");
            for (Config config : groovyScripts) {
                items.add(config.name, config.id);
            }
            return items;
        }

        public HttpResponse doCheckScriptId(StaplerRequest req, @AncestorInPath Item context, @QueryParameter String scriptId) {
            final Config config = ConfigFiles.getByIdOrNull(context, scriptId);
            if (config == null) {
                return FormValidation.error("You must select a valid groovy script");
            }

            String link = req.getContextPath();
            link = StringUtils.isNotBlank(context.getUrl()) ? link + "/" + context.getUrl() : link;
            link = link + "configfiles/show?id=" + scriptId;
            String html = "<a target=\"_blank\" href=\"" + link + "\">View Script</a>";

            //if (StringUtils.isNotBlank(argumentDetails)) {
            //    html = html + "<br />" + argumentDetails;
            //}

            // 1x16 spacer needed for IE since it doesn't support min-height
            html = "<div class='ok'><img src='" +
                    req.getContextPath() + Jenkins.RESOURCE_PATH + "/images/none.gif' height=16 width=1>" +
                    html + "</div>";

            return new DescriptionResponse(html);
        }

        /**
         * gets the argument description to be displayed on the screen when selecting a config in the dropdown
         *
         * @param scriptId
         *            the config id to get the arguments description for
         * @return the description
         */
        @JavaScriptMethod
        public JSONArray getArgs(String scriptId) {
            return null;
            /*
            final ManagementLink cfgFiles = this.getConfigFilesManagement();
            if (cfgFiles != null) {
                ConfigFilesManagement cfgFilesMgr = (ConfigFilesManagement) cfgFiles;
                for (Map.Entry<ConfigProvider,Collection<Config>> e : cfgFilesMgr.getGroupedConfigs().entrySet()) {
                    if (e.getKey().getContentType().equals(DefinedType.GROOVY)) {
                        List<Config> cfgs = (List) e.getValue();
                        for (Config cfg : cfgs) {
                            if (cfg.id.equals(scriptId)) {
                                org.jenkinsci.plugins.configfiles.groovy.GroovyScript script = (org.jenkinsci.plugins.configfiles.groovy.GroovyScript) cfg;
                                return JSONArray.fromObject(script.getArgs());
                            }
                        }
                    }
                }
            }
            return null;
            */
        }
    }
}
