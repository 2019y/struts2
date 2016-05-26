/*
 * Copyright 2002-2006,2009 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.opensymphony.xwork2.config.impl;

import com.opensymphony.xwork2.*;
import com.opensymphony.xwork2.config.*;
import com.opensymphony.xwork2.config.entities.*;
import com.opensymphony.xwork2.config.providers.InterceptorBuilder;
import com.opensymphony.xwork2.conversion.*;
import com.opensymphony.xwork2.conversion.impl.*;
import com.opensymphony.xwork2.factory.*;
import com.opensymphony.xwork2.inject.*;
import com.opensymphony.xwork2.ognl.OgnlReflectionProvider;
import com.opensymphony.xwork2.ognl.OgnlUtil;
import com.opensymphony.xwork2.ognl.OgnlValueStackFactory;
import com.opensymphony.xwork2.ognl.accessor.CompoundRootAccessor;
import com.opensymphony.xwork2.util.*;
import com.opensymphony.xwork2.util.fs.DefaultFileManager;
import com.opensymphony.xwork2.util.fs.DefaultFileManagerFactory;
import com.opensymphony.xwork2.util.location.LocatableProperties;
import com.opensymphony.xwork2.util.reflection.ReflectionProvider;
import ognl.PropertyAccessor;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts2.StrutsConstants;

import java.util.*;


/**
 * DefaultConfiguration
 *
 * @author Jason Carreira
 *         Created Feb 24, 2003 7:38:06 AM
 */
public class DefaultConfiguration implements Configuration {

    protected static final Logger LOG = LogManager.getLogger(DefaultConfiguration.class);


    // Programmatic Action Configurations
    protected Map<String, PackageConfig> packageContexts = new LinkedHashMap<>();
    protected RuntimeConfiguration runtimeConfiguration;
    protected Container container;
    protected String defaultFrameworkBeanName;
    protected Set<String> loadedFileNames = new TreeSet<>();
    protected List<UnknownHandlerConfig> unknownHandlerStack;


    ObjectFactory objectFactory;

    public DefaultConfiguration() {
        this("xwork");
    }

    public DefaultConfiguration(String defaultBeanName) {
        this.defaultFrameworkBeanName = defaultBeanName;
    }


    public PackageConfig getPackageConfig(String name) {
        return packageContexts.get(name);
    }

    public List<UnknownHandlerConfig> getUnknownHandlerStack() {
        return unknownHandlerStack;
    }

    public void setUnknownHandlerStack(List<UnknownHandlerConfig> unknownHandlerStack) {
        this.unknownHandlerStack = unknownHandlerStack;
    }

    public Set<String> getPackageConfigNames() {
        return packageContexts.keySet();
    }

    public Map<String, PackageConfig> getPackageConfigs() {
        return packageContexts;
    }

    public Set<String> getLoadedFileNames() {
        return loadedFileNames;
    }

    public RuntimeConfiguration getRuntimeConfiguration() {
        return runtimeConfiguration;
    }

    /**
     * @return the container
     */
    public Container getContainer() {
        return container;
    }

    public void addPackageConfig(String name, PackageConfig packageContext) {
        PackageConfig check = packageContexts.get(name);
        if (check != null) {
            if (check.getLocation() != null && packageContext.getLocation() != null
                    && check.getLocation().equals(packageContext.getLocation())) {
                LOG.debug("The package name '{}' is already been loaded by the same location and could be removed: {}",
                        name, packageContext.getLocation());
            } else {
                throw new ConfigurationException("The package name '" + name
                        + "' at location "+packageContext.getLocation()
                        + " is already been used by another package at location " + check.getLocation(),
                        packageContext);
            }
        }
        packageContexts.put(name, packageContext);
    }

    public PackageConfig removePackageConfig(String packageName) {
        return packageContexts.remove(packageName);
    }

    /**
     * Allows the configuration to clean up any resources used
     */
    public void destroy() {
        packageContexts.clear();
        loadedFileNames.clear();
    }

    public void rebuildRuntimeConfiguration() {
        runtimeConfiguration = buildRuntimeConfiguration();
    }

    /**
     * Calls the ConfigurationProviderFactory.getConfig() to tell it to reload the configuration and then calls
     * buildRuntimeConfiguration().
     *
     * @return list of package providers
     *
     * @throws ConfigurationException in case of any configuration errors
     */
    public synchronized List<PackageProvider> reloadContainer(List<ContainerProvider> providers) throws ConfigurationException {
        // 源码解析: 清空操作
        packageContexts.clear();
        loadedFileNames.clear();
        List<PackageProvider> packageProviders = new ArrayList<>();

        // 源码解析: 容器属性
        ContainerProperties props = new ContainerProperties();
        // 源码解析: 容器构建器
        ContainerBuilder builder = new ContainerBuilder();

        // 源码解析: 创建启动容器
        Container bootstrap = createBootstrapContainer(providers);
        for (final ContainerProvider containerProvider : providers)
        {
            // 源码解析: ContainerProvider依赖注入
            bootstrap.inject(containerProvider);
            // 源码解析: ContainerProvider初始化
            containerProvider.init(this);
            // 源码解析: 注册Bean和Propertie到容器
            containerProvider.register(builder, props);
        }

        // 源码解析: 属性注册到容器
        props.setConstants(builder);

        // 源码解析: 设置容器中Configuration类型的实例为当前实例
        builder.factory(Configuration.class, new Factory<Configuration>() {
            public Configuration create(Context context) throws Exception {
                return DefaultConfiguration.this;
            }
        });

        ActionContext oldContext = ActionContext.getContext();
        try {
            // Set the bootstrap container for the purposes of factory creation

            // 源码解析: 利用启动容器来创建ValueStackFactory工厂
            setContext(bootstrap);

            // 源码解析: 创建真正的容器
            container = builder.create(false);

            setContext(container);

            // 源码解析: 从容器中获取对象工厂
            objectFactory = container.getInstance(ObjectFactory.class);

            // Process the configuration providers first

            // 源码解析: 先处理ConfigurationProvider,
            for (final ContainerProvider containerProvider : providers)
            {
                if (containerProvider instanceof PackageProvider) {
                    // 源码解析: 依赖注入
                    container.inject(containerProvider);
                    // 源码解析: 加载struts的xml配置文件中的package配置
                    ((PackageProvider)containerProvider).loadPackages();
                    // 源码解析: 添加到PackageProvider列表中
                    packageProviders.add((PackageProvider)containerProvider);
                }
            }

            // Then process any package providers from the plugins

            /**
             * 源码解析: 处理插件中的PackageProvider
             *
             * struts2-convention-plugin插件, struts-plugin.xml配置两个PackageProvider
             * 1) org.apache.struts2.convention.ClasspathPackageProvider, 加载Struts2的注解配置
             * 2) org.apache.struts2.convention.ClasspathConfigurationProvider
             */
            Set<String> packageProviderNames = container.getInstanceNames(PackageProvider.class);
            for (String name : packageProviderNames) {
                // 源码解析: 从容器中获取对应的PackageProvider实例
                PackageProvider provider = container.getInstance(PackageProvider.class, name);
                // 源码解析: 依赖注入
                provider.init(this);
                // 源码解析: 加载package配置
                provider.loadPackages();
                // 源码解析: 添加到PackageProvider列表中
                packageProviders.add(provider);
            }

            // 源码解析: 重新构建运行时配置
            rebuildRuntimeConfiguration();
        } finally {
            if (oldContext == null) {
                ActionContext.setContext(null);
            }
        }
        return packageProviders;
    }

    protected ActionContext setContext(Container cont) {
        ActionContext context = ActionContext.getContext();
        if (context == null) {
            ValueStack vs = cont.getInstance(ValueStackFactory.class).createValueStack();
            context = new ActionContext(vs.getContext());
            ActionContext.setContext(context);
        }
        return context;
    }

    // 源码解析: 创建启动容器
    protected Container createBootstrapContainer(List<ContainerProvider> providers) {
        ContainerBuilder builder = new ContainerBuilder();
        boolean fmFactoryRegistered = false;

        // 源码解析: 文件管理器注册
        for (ContainerProvider provider : providers) {
            if (provider instanceof FileManagerProvider) {
                provider.register(builder, null);
            }
            if (provider instanceof FileManagerFactoryProvider) {
                provider.register(builder, null);
                fmFactoryRegistered = true;
            }
        }

        // 源码解析: 注册bean到容器
        builder.factory(ObjectFactory.class, Scope.SINGLETON);
        builder.factory(ActionFactory.class, DefaultActionFactory.class, Scope.SINGLETON);
        builder.factory(ResultFactory.class, DefaultResultFactory.class, Scope.SINGLETON);
        builder.factory(InterceptorFactory.class, DefaultInterceptorFactory.class, Scope.SINGLETON);
        builder.factory(com.opensymphony.xwork2.factory.ValidatorFactory.class, com.opensymphony.xwork2.factory.DefaultValidatorFactory.class, Scope.SINGLETON);
        builder.factory(ConverterFactory.class, DefaultConverterFactory.class, Scope.SINGLETON);
        builder.factory(UnknownHandlerFactory.class, DefaultUnknownHandlerFactory.class, Scope.SINGLETON);

        builder.factory(FileManager.class, "system", DefaultFileManager.class, Scope.SINGLETON);
        if (!fmFactoryRegistered) {
            builder.factory(FileManagerFactory.class, DefaultFileManagerFactory.class, Scope.SINGLETON);
        }
        builder.factory(ReflectionProvider.class, OgnlReflectionProvider.class, Scope.SINGLETON);
        builder.factory(ValueStackFactory.class, OgnlValueStackFactory.class, Scope.SINGLETON);

        builder.factory(XWorkConverter.class, Scope.SINGLETON);
        builder.factory(ConversionPropertiesProcessor.class, DefaultConversionPropertiesProcessor.class, Scope.SINGLETON);
        builder.factory(ConversionFileProcessor.class, DefaultConversionFileProcessor.class, Scope.SINGLETON);
        builder.factory(ConversionAnnotationProcessor.class, DefaultConversionAnnotationProcessor.class, Scope.SINGLETON);
        builder.factory(TypeConverterCreator.class, DefaultTypeConverterCreator.class, Scope.SINGLETON);
        builder.factory(TypeConverterHolder.class, DefaultTypeConverterHolder.class, Scope.SINGLETON);

        builder.factory(XWorkBasicConverter.class, Scope.SINGLETON);
        builder.factory(TypeConverter.class, XWorkConstants.COLLECTION_CONVERTER,  CollectionConverter.class, Scope.SINGLETON);
        builder.factory(TypeConverter.class, XWorkConstants.ARRAY_CONVERTER, ArrayConverter.class, Scope.SINGLETON);
        builder.factory(TypeConverter.class, XWorkConstants.DATE_CONVERTER, DateConverter.class, Scope.SINGLETON);
        builder.factory(TypeConverter.class, XWorkConstants.NUMBER_CONVERTER,  NumberConverter.class, Scope.SINGLETON);
        builder.factory(TypeConverter.class, XWorkConstants.STRING_CONVERTER, StringConverter.class, Scope.SINGLETON);

        builder.factory(TextParser.class, OgnlTextParser.class, Scope.SINGLETON);
        builder.factory(TextProvider.class, "system", DefaultTextProvider.class, Scope.SINGLETON);

        builder.factory(ObjectTypeDeterminer.class, DefaultObjectTypeDeterminer.class, Scope.SINGLETON);
        builder.factory(PropertyAccessor.class, CompoundRoot.class.getName(), CompoundRootAccessor.class, Scope.SINGLETON);
        builder.factory(OgnlUtil.class, Scope.SINGLETON);

        // 源码解析: 注册属性到容器
        builder.constant(XWorkConstants.DEV_MODE, "false");
        builder.constant(XWorkConstants.LOG_MISSING_PROPERTIES, "false");
        builder.constant(XWorkConstants.ENABLE_OGNL_EVAL_EXPRESSION, "false");
        builder.constant(XWorkConstants.ENABLE_OGNL_EXPRESSION_CACHE, "true");
        builder.constant(XWorkConstants.RELOAD_XML_CONFIGURATION, "false");

        // 源码解析: 创建容器并返回
        return builder.create(true);
    }

    /**
     * <p>
     * This builds the internal runtime configuration used by Xwork for finding and configuring Actions from the
     * programmatic configuration data structures. All of the old runtime configuration will be discarded and rebuilt.
     * </p>
     *
     * <p>
     * It basically flattens the data structures to make the information easier to access.  It will take
     * an {@link ActionConfig} and combine its data with all inherited dast.  For example, if the {@link ActionConfig}
     * is in a package that contains a global result and it also contains a result, the resulting {@link ActionConfig}
     * will have two results.
     * </p>
     *
     * @return runtime configuration
     * @throws ConfigurationException in case of any configuration errors
     */
    protected synchronized RuntimeConfiguration buildRuntimeConfiguration() throws ConfigurationException {
        Map<String, Map<String, ActionConfig>> namespaceActionConfigs = new LinkedHashMap<>();
        Map<String, String> namespaceConfigs = new LinkedHashMap<>();

        for (PackageConfig packageConfig : packageContexts.values()) {

            if (!packageConfig.isAbstract()) {
                String namespace = packageConfig.getNamespace();
                Map<String, ActionConfig> configs = namespaceActionConfigs.get(namespace);

                if (configs == null) {
                    configs = new LinkedHashMap<>();
                }

                Map<String, ActionConfig> actionConfigs = packageConfig.getAllActionConfigs();

                for (Object o : actionConfigs.keySet()) {
                    String actionName = (String) o;
                    ActionConfig baseConfig = actionConfigs.get(actionName);
                    configs.put(actionName, buildFullActionConfig(packageConfig, baseConfig));
                }

                namespaceActionConfigs.put(namespace, configs);
                if (packageConfig.getFullDefaultActionRef() != null) {
                    namespaceConfigs.put(namespace, packageConfig.getFullDefaultActionRef());
                }
            }
        }

        PatternMatcher<int[]> matcher = container.getInstance(PatternMatcher.class);
        return new RuntimeConfigurationImpl(Collections.unmodifiableMap(namespaceActionConfigs),
                Collections.unmodifiableMap(namespaceConfigs), matcher);
    }

    private void setDefaultResults(Map<String, ResultConfig> results, PackageConfig packageContext) {
        String defaultResult = packageContext.getFullDefaultResultType();

        for (Map.Entry<String, ResultConfig> entry : results.entrySet()) {

            if (entry.getValue() == null) {
                ResultTypeConfig resultTypeConfig = packageContext.getAllResultTypeConfigs().get(defaultResult);
                entry.setValue(new ResultConfig.Builder(null, resultTypeConfig.getClassName()).build());
            }
        }
    }

    /**
     * Builds the full runtime actionconfig with all of the defaults and inheritance
     *
     * @param packageContext the PackageConfig which holds the base config we're building from
     * @param baseConfig     the ActionConfig which holds only the configuration specific to itself, without the defaults
     *                       and inheritance
     * @return a full ActionConfig for runtime configuration with all of the inherited and default params
     * @throws com.opensymphony.xwork2.config.ConfigurationException
     *
     */

    // 构建完整的运行时action配置
    private ActionConfig buildFullActionConfig(PackageConfig packageContext, ActionConfig baseConfig) throws ConfigurationException {
        Map<String, String> params = new TreeMap<>(baseConfig.getParams());
        Map<String, ResultConfig> results = new TreeMap<>();

        // 添加全局result
        if (!baseConfig.getPackageName().equals(packageContext.getName()) && packageContexts.containsKey(baseConfig.getPackageName())) {
            results.putAll(packageContexts.get(baseConfig.getPackageName()).getAllGlobalResults());
        } else {
            results.putAll(packageContext.getAllGlobalResults());
        }

        // 添加action配置的result
       	results.putAll(baseConfig.getResults());

        // Result为空, 设置默认值
        setDefaultResults(results, packageContext);

        List<InterceptorMapping> interceptors = new ArrayList<>(baseConfig.getInterceptors());

        if (interceptors.size() <= 0) {
            String defaultInterceptorRefName = packageContext.getFullDefaultInterceptorRef();

            if (defaultInterceptorRefName != null) {
                interceptors.addAll(InterceptorBuilder.constructInterceptorReference(new PackageConfig.Builder(packageContext), defaultInterceptorRefName,
                        new LinkedHashMap<String, String>(), packageContext.getLocation(), objectFactory));
            }
        }

        String methodRegex = container.getInstance(String.class, StrutsConstants.STRUTS_SMI_METHOD_REGEX);
        if (methodRegex == null) {
            methodRegex = ActionConfig.DEFAULT_METHOD_REGEX;
        }

        LOG.debug("Using pattern [{}] to match allowed methods when SMI is disabled!", methodRegex);

        return new ActionConfig.Builder(baseConfig)
            .addParams(params)
            .addResultConfigs(results)
            .defaultClassName(packageContext.getDefaultClassRef())  // fill in default if non class has been provided
            .interceptors(interceptors)
            .setStrictMethodInvocation(packageContext.isStrictMethodInvocation())
            .setDefaultMethodRegex(methodRegex)
            .addExceptionMappings(packageContext.getAllExceptionMappingConfigs())
            .build();
    }


    private static class RuntimeConfigurationImpl implements RuntimeConfiguration {

        private Map<String, Map<String, ActionConfig>> namespaceActionConfigs;
        private Map<String, ActionConfigMatcher> namespaceActionConfigMatchers;
        private NamespaceMatcher namespaceMatcher;
        private Map<String, String> namespaceConfigs;

        public RuntimeConfigurationImpl(Map<String, Map<String, ActionConfig>> namespaceActionConfigs,
                                        Map<String, String> namespaceConfigs,
                                        PatternMatcher<int[]> matcher) {
            this.namespaceActionConfigs = namespaceActionConfigs;
            this.namespaceConfigs = namespaceConfigs;

            this.namespaceActionConfigMatchers = new LinkedHashMap<>();
            this.namespaceMatcher = new NamespaceMatcher(matcher, namespaceActionConfigs.keySet());

            for (String ns : namespaceActionConfigs.keySet()) {
                namespaceActionConfigMatchers.put(ns, new ActionConfigMatcher(matcher, namespaceActionConfigs.get(ns), true));
            }
        }


        /**
         * Gets the configuration information for an action name, or returns null if the
         * name is not recognized.
         *
         * @param name      the name of the action
         * @param namespace the namespace for the action or null for the empty namespace, ""
         * @return the configuration information for action requested
         */
        public ActionConfig getActionConfig(String namespace, String name) {
            ActionConfig config = findActionConfigInNamespace(namespace, name);

            // try wildcarded namespaces
            if (config == null) {
                NamespaceMatch match = namespaceMatcher.match(namespace);
                if (match != null) {
                    config = findActionConfigInNamespace(match.getPattern(), name);

                    // If config found, place all the matches found in the namespace processing in the action's parameters
                    if (config != null) {
                        config = new ActionConfig.Builder(config)
                                .addParams(match.getVariables())
                                .build();
                    }
                }
            }

            // fail over to empty namespace
            if (config == null && StringUtils.isNotBlank(namespace)) {
                config = findActionConfigInNamespace("", name);
            }


            return config;
        }

        private ActionConfig findActionConfigInNamespace(String namespace, String name) {
            ActionConfig config = null;
            if (namespace == null) {
                namespace = "";
            }
            Map<String, ActionConfig> actions = namespaceActionConfigs.get(namespace);
            if (actions != null) {
                config = actions.get(name);
                // Check wildcards
                if (config == null) {
                    config = namespaceActionConfigMatchers.get(namespace).match(name);
                    // fail over to default action
                    if (config == null) {
                        String defaultActionRef = namespaceConfigs.get(namespace);
                        if (defaultActionRef != null) {
                            config = actions.get(defaultActionRef);
                        }
                    }
                }
            }
            return config;
        }

        /**
         * Gets the configuration settings for every action.
         *
         * @return a Map of namespace - > Map of ActionConfig objects, with the key being the action name
         */
        public Map<String, Map<String, ActionConfig>>  getActionConfigs() {
            return namespaceActionConfigs;
        }

        @Override
        public String toString() {
            StringBuilder buff = new StringBuilder("RuntimeConfiguration - actions are\n");

            for (String namespace : namespaceActionConfigs.keySet()) {
                Map<String, ActionConfig> actionConfigs = namespaceActionConfigs.get(namespace);

                for (String s : actionConfigs.keySet()) {
                    buff.append(namespace).append("/").append(s).append("\n");
                }
            }

            return buff.toString();
        }
    }

    class ContainerProperties extends LocatableProperties {
        private static final long serialVersionUID = -7320625750836896089L;

        @Override
        public Object setProperty(String key, String value) {
            String oldValue = getProperty(key);
            if (LOG.isInfoEnabled() && oldValue != null && !oldValue.equals(value) && !defaultFrameworkBeanName.equals(oldValue)) {
                LOG.info("Overriding property {} - old value: {} new value: {}", key, oldValue, value);
            }
            return super.setProperty(key, value);
        }

        public void setConstants(ContainerBuilder builder) {
            for (Object keyobj : keySet()) {
                String key = (String)keyobj;
                builder.factory(String.class, key, new LocatableConstantFactory<>(getProperty(key), getPropertyLocation(key)));
            }
        }
    }
}
