/*
 * The MIT License
 *
 * Copyright (c) 2014, CloudBees, Inc.
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
package org.jenkinsci.plugins.sysloglogger;

import com.cloudbees.syslog.Facility;
import com.cloudbees.syslog.MessageFormat;
import com.cloudbees.syslog.Severity;
import com.cloudbees.syslog.integration.jul.SyslogHandler;
import com.cloudbees.syslog.integration.jul.util.LevelHelper;
import com.cloudbees.syslog.sender.SyslogMessageSender;
import com.cloudbees.syslog.sender.TcpSyslogMessageSender;
import com.cloudbees.syslog.sender.UdpSyslogMessageSender;
import hudson.Extension;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.logging.*;

/**
 * <p>
 * Send Jenkins CI logs to a Syslog Server.
 * </p>
 * <p>
 * Features:
 * </p>
 * <ul>
 * <li>Network protocol: UDP (TCP and TLS should come soon)</li>
 * <li>Syslog message format: RFC 3164 and RFC 5424</li>
 * </ul>
 *
 * @author Cyrille Le Clerc
 */
@Extension
public class SyslogLoggerPlugin extends GlobalConfiguration {

    public static final SyslogTransport DEFAULT_SYSLOG_TRANSPORT = SyslogTransport.UDP;
    public static final int DEFAULT_SYSLOG_SERVER_PORT = 514;
    public static final Level DEFAULT_LEVEL_FILTER = Level.FINE;
    public static final String DEFAULT_APP_NAME = "jenkins";
    public static final Facility DEFAULT_FACILITY = Facility.USER;
    public static final MessageFormat DEFAULT_MESSAGE_FORMAT = MessageFormat.RFC_3164;

    enum SyslogTransport {
        UDP("UDP") {
            @Override
            public SyslogMessageSender create() {
                return new UdpSyslogMessageSender();
            }
        }, TCP("TCP"){
            @Override
            public SyslogMessageSender create() {
                return new TcpSyslogMessageSender();
            }
        }, TCP_SSL("TCP + SSL"){
            @Override
            public SyslogMessageSender create() {
                TcpSyslogMessageSender tcpSyslogMessageSender = new TcpSyslogMessageSender();
                tcpSyslogMessageSender.setSsl(true);
                return tcpSyslogMessageSender;
            }
        };

        SyslogTransport(String label) {
            this.label = label;
        }

        final String label;

        public String label() {
            return label;
        }

        public abstract SyslogMessageSender create();
    }

    private SyslogTransport syslogTransport = DEFAULT_SYSLOG_TRANSPORT;
    private String syslogServerHostname;
    private int syslogServerPort = DEFAULT_SYSLOG_SERVER_PORT;
    private Level levelFilter = DEFAULT_LEVEL_FILTER;
    private String appName = DEFAULT_APP_NAME;
    private String messageHostname;
    private Facility facility = DEFAULT_FACILITY;
    private MessageFormat messageFormat = DEFAULT_MESSAGE_FORMAT;

    public SyslogLoggerPlugin() {
        load();
        applySettings();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        syslogTransport = defaultValue(SyslogTransport.valueOf(formData.getString("syslogTransport")), SyslogTransport.UDP);
        syslogServerHostname = trimToNull(formData.optString("syslogServerHostname"));
        syslogServerPort = defaultValue(formData.optInt("syslogServerPort"), DEFAULT_SYSLOG_SERVER_PORT);
        levelFilter = defaultValue(LevelHelper.findLevel(trimToNull(formData.optString("levelFilter"))), DEFAULT_LEVEL_FILTER);
        appName = defaultValue(trimToNull(formData.optString("appName")), DEFAULT_APP_NAME);
        messageHostname = trimToNull(formData.optString("messageHostname"));
        facility = defaultValue(Facility.fromLabel(trimToNull(formData.optString("facility"))), DEFAULT_FACILITY);
        messageFormat = defaultValue(MessageFormat.valueOf(formData.optString("messageFormat")), DEFAULT_MESSAGE_FORMAT);
        save();

        applySettings();
        return true;
    }


    private void applySettings() {

        Logger rootLogger = Logger.getLogger("");

        // remove existing SyslogHandler
        for (Handler handler : rootLogger.getHandlers()) {
            if (handler instanceof SyslogHandler) {
                SyslogHandler syslogHandler = (SyslogHandler) handler;
                rootLogger.removeHandler(syslogHandler);
            }
        }

        if (syslogServerHostname == null || syslogServerHostname.isEmpty()) {
            LOGGER.fine("SyslogLogger not configured");
            return;
        }

        SyslogMessageSender syslogMessageSender = syslogTransport.create();
        if (syslogMessageSender instanceof UdpSyslogMessageSender) {
            UdpSyslogMessageSender messageSender = (UdpSyslogMessageSender) syslogMessageSender;
            messageSender.setSyslogServerHostname(syslogServerHostname);
            messageSender.setSyslogServerPort(syslogServerPort);
            messageSender.setMessageFormat(messageFormat);
        } else if (syslogMessageSender instanceof TcpSyslogMessageSender) {
            TcpSyslogMessageSender messageSender = (TcpSyslogMessageSender) syslogMessageSender;
            messageSender.setSyslogServerHostname(syslogServerHostname);
            messageSender.setSyslogServerPort(syslogServerPort);
            messageSender.setMessageFormat(messageFormat);
        } else {
            throw new IllegalStateException("Unsupported SyslogMessageSender");
        }

        SyslogHandler handler = new SyslogHandler(syslogMessageSender, levelFilter, null);
        handler.setAppName(appName);
        handler.setMessageHostname(messageHostname);
        handler.setFacility(facility);
        // TODO support customization of the j.u.l. formatter
        handler.setFormatter(new SimpleFormatter());

        String msg = "Jenkins configured to output log messages to syslog server " + syslogServerHostname + ":" + syslogServerPort + " on transport " + syslogTransport.label();
        LogRecord logRecord = new LogRecord(Level.INFO, msg);
        logRecord.setLoggerName(LOGGER.getName());
        handler.publish(logRecord);

        LOGGER.info(msg);

        rootLogger.addHandler(handler);
    }

    public ListBoxModel doFillLevelFilterItems() {
        ListBoxModel items = new ListBoxModel();
        Level[] levels = LevelHelper.levels.toArray(new Level[0]);
        Arrays.sort(levels, LevelHelper.comparator());
        for (Level level : levels) {
            items.add(level.getName());
        }
        return items;
    }

    public ListBoxModel doFillMessageFormatItems() {
        ListBoxModel items = new ListBoxModel();
        for (MessageFormat messageFormat : MessageFormat.values()) {
            items.add(messageFormat.name());
        }
        return items;
    }

    public ListBoxModel doFillSeverityItems() {
        ListBoxModel items = new ListBoxModel();
        Severity[] severities = Severity.values();
        Arrays.sort(severities, Severity.comparator());
        for (Severity severity : severities) {
            items.add(severity.label());
        }
        return items;
    }

    public ListBoxModel doFillFacilityItems() {
        ListBoxModel items = new ListBoxModel();
        Facility[] facilities = Facility.values();
        Arrays.sort(facilities, Facility.comparator());
        for (Facility facility : facilities) {
            items.add(facility.label());
        }
        return items;
    }

    public ListBoxModel doFillSyslogTransportItems() {
        ListBoxModel items = new ListBoxModel();
        for(SyslogTransport transport:SyslogTransport.values()) {
            items.add(transport.label(), transport.name());
        }
        return items;
    }

    public String getDisplayName() {
        return "Syslog Logger";
    }

    public String getSyslogServerHostname() {
        return syslogServerHostname;
    }

    public int getSyslogServerPort() {
        return syslogServerPort;
    }

    public String getLevelFilter() {
        return levelFilter == null ? null : levelFilter.getName();
    }

    public String getAppName() {
        return appName;
    }

    public String getMessageHostname() {
        return messageHostname;
    }

    public String getFacility() {
        return facility == null ? null : facility.label();
    }

    public String getMessageFormat() {
        return messageFormat == null ? null : messageFormat.name();
    }

    public String getSyslogTransport() {
        return syslogTransport == null ? null : syslogTransport.name();
    }

    public void setSyslogServerHostname(String syslogServerHostname) {
        this.syslogServerHostname = syslogServerHostname;
    }

    public void setSyslogServerPort(int syslogServerPort) {
        this.syslogServerPort = syslogServerPort;
    }

    public void setLevelFilter(String levelFilter) {
        this.levelFilter = levelFilter == null ? null : Level.parse(levelFilter);
    }

    public void setMessageHostname(String messageHostname) {
        this.messageHostname = messageHostname;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setFacility(String facility) {
        this.facility = facility == null ? null : Facility.fromLabel(facility);
    }

    public void setSyslogTransport(String syslogTransport) {
        this.syslogTransport = syslogTransport == null ? null : SyslogTransport.valueOf(syslogTransport);
    }

    public void setMessageFormat(String messageFormat) {
        this.messageFormat = messageFormat == null ? null : MessageFormat.valueOf(messageFormat);
    }

    @Override
    public String toString() {
        return "SyslogLoggerPlugin{" +
                "syslogServerHostname='" + syslogServerHostname + '\'' +
                ", syslogServerPort=" + syslogServerPort +
                ", syslogTransport=" + syslogTransport +
                ", levelFilter=" + levelFilter +
                ", appName='" + appName + '\'' +
                ", messageHostname='" + messageHostname + '\'' +
                ", facility=" + facility +
                '}';
    }

    protected static final Logger LOGGER = Logger.getLogger(SyslogLoggerPlugin.class.getName());

    @Nullable
    public static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        } else {
            return value;
        }
    }

    @Nullable
    public static <T> T defaultValue(T value, T defaultValue) {
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }

    }
}
