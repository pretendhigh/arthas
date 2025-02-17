package com.taobao.arthas.core.command.logger;

import static com.taobao.text.ui.Element.label;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.taobao.arthas.common.IOUtils;
import com.taobao.arthas.common.ReflectUtils;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ClassLoaderUtils;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.middleware.logger.Logger;
import com.taobao.text.Decoration;
import com.taobao.text.ui.TableElement;
import com.taobao.text.util.RenderUtil;

/**
 * logger command
 * 
 * TODO support log4j2
 * 
 * @author hengyunabc 2019-09-04
 *
 */
@Name("logger")
@Summary("Print logger info, and update the logger level")
@Description("\nExamples:\n" + "  logger\n" + "  logger -c 327a647b\n" + "  logger -c 327a647b --name ROOT --level debug\n"
                + Constants.WIKI + Constants.WIKI_HOME + "logger")
public class LoggerCommand extends AnnotatedCommand {
    private static final Logger logger = LogUtil.getArthasLogger();

    private static byte[] LoggerHelperBytes;
    private static byte[] Log4jHelperBytes;
    private static byte[] LogbackHelperBytes;

    private static Map<Class<?>, byte[]> classToBytesMap = new HashMap<Class<?>, byte[]>();

    static {
        LoggerHelperBytes = loadClassBytes(LoggerHelper.class);
        Log4jHelperBytes = loadClassBytes(Log4jHelper.class);
        LogbackHelperBytes = loadClassBytes(LogbackHelper.class);

        classToBytesMap.put(LoggerHelper.class, LoggerHelperBytes);
        classToBytesMap.put(Log4jHelper.class, Log4jHelperBytes);
        classToBytesMap.put(LogbackHelper.class, LogbackHelperBytes);
    }

    private String name;

    private String hashCode;

    private String level;

    /**
     * include the logger don't have appender, default false.
     */
    private boolean includeNoAppender;

    /**
     * include the arthas logger, default false.
     */
    private boolean includeArthasLogger;

    @Option(shortName = "n", longName = "name")
    @Description("logger name")
    public void setName(String name) {
        this.name = name;
    }

    @Option(shortName = "c", longName = "classloader")
    @Description("classLoader hashcode, if no value is set, default value is SystemClassLoader")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Option(shortName = "l", longName = "level")
    @Description("set logger level")
    public void setLevel(String level) {
        this.level = level;
    }

    @Option(longName = "include-no-appender", flag = true)
    @Description("include the loggers don't have appender, default value false")
    public void setHaveAppender(boolean includeNoAppender) {
        this.includeNoAppender = includeNoAppender;
    }

    @Option(longName = "include-arthas-logger", flag = true)
    @Description("include the arthas loggers, default value false")
    public void setIncludeArthasLogger(boolean includeArthasLogger) {
        this.includeArthasLogger = includeArthasLogger;
    }

    @Override
    public void process(CommandProcess process) {
        int status = 0;
        try {
            if (this.name != null && this.level != null) {
                level(process);
            } else {
                loggers(process, name);
            }
        } finally {
            process.end(status);
        }
    }

    public void level(CommandProcess process) {
        Instrumentation inst = process.session().getInstrumentation();
        boolean result = false;
        try {
            Boolean updateResult = this.updateLevel(inst, Log4jHelper.class);
            if (Boolean.TRUE.equals(updateResult)) {
                result = true;
            }
        } catch (Throwable e) {
            logger.error("arthas", "logger command update log4j level error", e);
        }

        try {
            Boolean updateResult = this.updateLevel(inst, LogbackHelper.class);
            if (Boolean.TRUE.equals(updateResult)) {
                result = true;
            }
        } catch (Throwable e) {
            logger.error("arthas", "logger command update logback level error", e);
        }

        if (result) {
            process.write("update logger level success.\n");
        } else {
            process.write("update logger level fail.\n");
        }
    }

    public void loggers(CommandProcess process, String name) {
        Map<ClassLoader, LoggerTypes> classLoaderLoggerMap = new LinkedHashMap<ClassLoader, LoggerTypes>();

        for (Class<?> clazz : process.session().getInstrumentation().getAllLoadedClasses()) {
            String className = clazz.getName();
            ClassLoader classLoader = clazz.getClassLoader();

            // skip the arthas classloader
            if (this.includeArthasLogger == false && classLoader != null && this.getClass().getClassLoader().getClass()
                            .getName().equals(classLoader.getClass().getName())) {
                continue;
            }

            // if special classloader
            if (this.hashCode != null && !this.hashCode.equals(StringUtils.classLoaderHash(clazz))) {
                continue;
            }

            if (classLoader != null) {
                LoggerTypes loggerTypes = classLoaderLoggerMap.get(classLoader);
                if (loggerTypes == null) {
                    loggerTypes = new LoggerTypes();
                    classLoaderLoggerMap.put(classLoader, loggerTypes);
                }
                if ("org.apache.log4j.Logger".equals(className)) {
                    loggerTypes.addType(LoggerType.LOG4J);
                } else if ("ch.qos.logback.classic.Logger".equals(className)) {
                    loggerTypes.addType(LoggerType.LOGBACK);
                }
            }
        }

        for (Entry<ClassLoader, LoggerTypes> entry : classLoaderLoggerMap.entrySet()) {
            ClassLoader classLoader = entry.getKey();
            LoggerTypes loggerTypes = entry.getValue();

            if (loggerTypes.contains(LoggerType.LOG4J)) {
                Map<String, Map<String, Object>> loggerInfoMap = loggerInfo(classLoader, Log4jHelper.class);
                String renderResult = renderLoggerInfo(loggerInfoMap, process.width());

                process.write(renderResult);
            }

            if (loggerTypes.contains(LoggerType.LOGBACK)) {
                Map<String, Map<String, Object>> loggerInfoMap = loggerInfo(classLoader, LogbackHelper.class);
                String renderResult = renderLoggerInfo(loggerInfoMap, process.width());

                process.write(renderResult);
            }

        }

    }

    private String renderLoggerInfo(Map<String, Map<String, Object>> loggerInfos, int width) {
        StringBuilder sb = new StringBuilder(8192);

        for (Entry<String, Map<String, Object>> entry : loggerInfos.entrySet()) {
            Map<String, Object> info = entry.getValue();

            TableElement table = new TableElement(2, 10).leftCellPadding(1).rightCellPadding(1);
            TableElement appendersTable = new TableElement().rightCellPadding(1);

            Class<?> clazz = (Class<?>) info.get(LoggerHelper.clazz);
            table.row(label(LoggerHelper.name).style(Decoration.bold.bold()), label("" + info.get(LoggerHelper.name)))
                            .row(label(LoggerHelper.clazz).style(Decoration.bold.bold()), label("" + clazz.getName()))
                            .row(label(LoggerHelper.classLoader).style(Decoration.bold.bold()),
                                            label("" + clazz.getClassLoader()))
                            .row(label(LoggerHelper.classLoaderHash).style(Decoration.bold.bold()),
                                            label("" + StringUtils.classLoaderHash(clazz)))
                            .row(label(LoggerHelper.level).style(Decoration.bold.bold()),
                                            label("" + info.get(LoggerHelper.level)))
                            .row(label(LoggerHelper.effectiveLevel).style(Decoration.bold.bold()),
                                            label("" + info.get(LoggerHelper.effectiveLevel)))
                            .row(label(LoggerHelper.additivity).style(Decoration.bold.bold()),
                                            label("" + info.get(LoggerHelper.additivity)))
                            .row(label(LoggerHelper.codeSource).style(Decoration.bold.bold()),
                                            label("" + info.get(LoggerHelper.codeSource)));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> appenders = (List<Map<String, Object>>) info.get(LoggerHelper.appenders);
            if (appenders != null && !appenders.isEmpty()) {

                for (Map<String, Object> appenderInfo : appenders) {
                    Class<?> appenderClass = (Class<?>) appenderInfo.get(LoggerHelper.clazz);

                    appendersTable.row(label(LoggerHelper.name).style(Decoration.bold.bold()),
                                    label("" + appenderInfo.get(LoggerHelper.name)));
                    appendersTable.row(label(LoggerHelper.clazz), label("" + appenderClass.getName()));
                    appendersTable.row(label(LoggerHelper.classLoader), label("" + appenderClass.getClassLoader()));
                    appendersTable.row(label(LoggerHelper.classLoaderHash),
                                    label("" + StringUtils.classLoaderHash(appenderClass)));
                    if (appenderInfo.get(LoggerHelper.file) != null) {
                        appendersTable.row(label(LoggerHelper.file), label("" + appenderInfo.get(LoggerHelper.file)));
                    }
                    if (appenderInfo.get(LoggerHelper.target) != null) {
                        appendersTable.row(label(LoggerHelper.target),
                                        label("" + appenderInfo.get(LoggerHelper.target)));
                    }
                    if (appenderInfo.get(LoggerHelper.appenderRef) != null) {
                        appendersTable.row(label(LoggerHelper.appenderRef),
                                        label("" + appenderInfo.get(LoggerHelper.appenderRef)));
                    }
                }

                table.row(label("appenders").style(Decoration.bold.bold()), appendersTable);
            }

            sb.append(RenderUtil.render(table, width)).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loggerInfo(ClassLoader classLoader, Class<?> helperClass) {
        Map<String, Map<String, Object>> loggers = Collections.emptyMap();
        try {
            classLoader.loadClass(helperClass.getName());
        } catch (ClassNotFoundException e) {
            try {
                ReflectUtils.defineClass(helperClass.getName(), classToBytesMap.get(helperClass), classLoader);
            } catch (Exception e1) {
                // ignore
            }
        }

        try {
            Class<?> clazz = classLoader.loadClass(helperClass.getName());
            Method getLoggersMethod = clazz.getMethod("getLoggers", new Class<?>[] { String.class, boolean.class });
            loggers = (Map<String, Map<String, Object>>) getLoggersMethod.invoke(null,
                            new Object[] { name, includeNoAppender });
        } catch (Throwable e) {
            // ignore
        }
        return loggers;
    }

    private Boolean updateLevel(Instrumentation inst, Class<?> helperClass) throws Exception {
        ClassLoader classLoader = null;
        if (hashCode == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        } else {
            classLoader = ClassLoaderUtils.getClassLoader(inst, hashCode);
        }

        Class<?> clazz = classLoader.loadClass(helperClass.getName());
        Method updateLevelMethod = clazz.getMethod("updateLevel", new Class<?>[] { String.class, String.class });
        return (Boolean) updateLevelMethod.invoke(null, new Object[] { this.name, this.level });

    }

    static enum LoggerType {
        LOG4J, LOGBACK
    }

    static class LoggerTypes {
        Set<LoggerType> types = new HashSet<LoggerType>();

        public Collection<LoggerType> types() {
            return types;
        }

        public void addType(LoggerType type) {
            types.add(type);
        }

        public boolean contains(LoggerType type) {
            return types.contains(type);
        }
    }

    private static byte[] loadClassBytes(Class<?> clazz) {
        try {
            InputStream stream = LoggerCommand.class.getClassLoader()
                            .getResourceAsStream(clazz.getName().replace('.', '/') + ".class");

            return IOUtils.getBytes(stream);
        } catch (IOException e) {
            // ignore
            return null;
        }
    }
}
