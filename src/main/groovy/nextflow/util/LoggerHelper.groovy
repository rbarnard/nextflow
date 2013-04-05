/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.CoreConstants
import ch.qos.logback.core.LayoutBase
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.joran.spi.NoAutoStart
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.RolloverFailure
import ch.qos.logback.core.spi.FilterReply
import nextflow.Const
import org.slf4j.LoggerFactory

/**
 * Helper methods to setup the logging subsystem
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class LoggerHelper {


    /**
     * Configure the client application logging subsytem.
     * <p>
     *     It looks on the cli argument for the following options
     *     <li>--debug
     *     <li>--trace
     *
     * <p>
     *     On both of them can be optionally specified a comma separated list of packages to which
     *     apply the specify logging level
     *
     *
     * @param args The app cli arguments as entered by the user.
     */
    static void configureLogger(List<String> debugConf, List<String> traceConf) {

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()

        // Reset all the logger
        def root = loggerContext.getLogger('ROOT')
        root.detachAndStopAllAppenders()

        // -- define the console appender
        Map<String,Level> packages = [:]
        packages[Const.MAIN_PACKAGE] = Level.INFO
        packages['akka'] = Level.WARN
        debugConf?.each { packages[it] = Level.DEBUG }
        traceConf?.each { packages[it] = Level.TRACE }

        def filter = new LoggerPackageFilter( packages )
        filter.setContext(loggerContext)
        filter.start()

        def consoleAppender = new ConsoleAppender()
        consoleAppender.setContext(loggerContext)
        consoleAppender.setEncoder( new LayoutWrappingEncoder( layout: new PrettyConsoleLayout() ) )
        consoleAppender.addFilter(filter)
        consoleAppender.start()

        // -- the file appender
        def fileName = ".${Const.MAIN_PACKAGE}.log"
        def fileAppender = new RollingFileAppender()
        def rollingPolicy = new  FixedWindowRollingPolicy( )
        rollingPolicy.fileNamePattern = "${fileName}.%d{yyyy-MM-dd}.%i"
        rollingPolicy.setContext(loggerContext)
        rollingPolicy.setParent(fileAppender)
        rollingPolicy.setMinIndex(1)
        rollingPolicy.setMaxIndex(9)

        //timeBasedPolicy.setTimeBasedFileNamingAndTriggeringPolicy( new StartupTimeBasedTriggeringPolicy() )
        rollingPolicy.start()

        def encoder = new PatternLayoutEncoder()
        encoder.setPattern('%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n')
        encoder.setContext(loggerContext)
        encoder.start()

        fileAppender.file = fileName
        //fileAppender.rollingPolicy = timeBasedPolicy
        fileAppender.encoder = encoder
        fileAppender.setContext(loggerContext)
        fileAppender.setTriggeringPolicy(new StartupTimeBasedTriggeringPolicy())
        fileAppender.start()

        // -- configure the ROOT logger
        root.setLevel(Level.INFO)
        root.addAppender(fileAppender)
        root.addAppender(consoleAppender)

        // -- main package logger
        def mainLevel = packages[Const.MAIN_PACKAGE]
        def logger = loggerContext.getLogger(Const.MAIN_PACKAGE)
        logger.setLevel( mainLevel == Level.TRACE ? Level.TRACE : Level.DEBUG )
        logger.setAdditive(false)
        logger.addAppender(fileAppender)
        logger.addAppender(consoleAppender)


        // -- debug packages specified by the user
        debugConf?.each { String clazz ->
            logger = loggerContext.getLogger( clazz )
            logger.setLevel(Level.DEBUG)
            logger.setAdditive(false)
            logger.addAppender(fileAppender)
            logger.addAppender(consoleAppender)
        }

        // -- trace packages specified by the user
        traceConf?.each { String clazz ->
            logger = loggerContext.getLogger( clazz )
            logger.setLevel(Level.TRACE)
            logger.setAdditive(false)
            logger.addAppender(fileAppender)
            logger.addAppender(consoleAppender)
        }

    }



    /*
     * Filters the logging event based on the level assigned to a specific 'package'
     */
    static class LoggerPackageFilter extends Filter<ILoggingEvent> {

        Map<String,Level> packages

        LoggerPackageFilter( Map<String,Level> packages )  {
            this.packages = packages
        }

        @Override
        FilterReply decide(ILoggingEvent event) {

            if (!isStarted()) {
                return FilterReply.NEUTRAL;
            }

            def logger = event.getLoggerName()
            def level = event.getLevel()
            for( def entry : packages ) {
                if ( logger.startsWith( entry.key ) && level.isGreaterOrEqual(entry.value) ) {
                    return FilterReply.NEUTRAL
                }
            }

            return FilterReply.DENY
        }
    }

    /*
     * Do not print INFO level prefix, used to print logging information
     * to the application stdout
     */
    static class PrettyConsoleLayout extends LayoutBase<ILoggingEvent> {

        public String doLayout(ILoggingEvent event) {
            StringBuilder buffer = new StringBuilder(128);
            if( event.getLevel() != Level.INFO ) {
                buffer.append( event.getLevel().toString() ) .append(": ")
            }

            return buffer
                    .append(event.getFormattedMessage())
                    .append(CoreConstants.LINE_SEPARATOR)
                    .toString()
        }
    }


    @NoAutoStart
    static public class StartupTimeBasedTriggeringPolicy extends DefaultTimeBasedFileNamingAndTriggeringPolicy {

        @Override
        public void start() {
            super.start();
            nextCheck = 0L;
            isTriggeringEvent(null, null);
            try {
                tbrp.rollover();
            } catch (RolloverFailure e) {
                //Do nothing
            }
        }

    }

}
