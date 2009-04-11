package ch.cyberduck.core.ftp;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import com.apple.cocoa.foundation.NSBundle;

import ch.cyberduck.core.*;
import ch.cyberduck.core.ftp.parser.CompositeFileEntryParser;
import ch.cyberduck.core.ftp.parser.LaxUnixFTPEntryParser;
import ch.cyberduck.core.ftp.parser.RumpusFTPEntryParser;

import org.apache.commons.net.ftp.Configurable;
import org.apache.commons.net.ftp.FTPFileEntryParser;
import org.apache.commons.net.ftp.parser.NetwareFTPEntryParser;
import org.apache.commons.net.ftp.parser.ParserInitializationException;
import org.apache.commons.net.ftp.parser.UnixFTPEntryParser;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import com.enterprisedt.net.ftp.*;

/**
 * Opens a connection to the remote server via ftp protocol
 *
 * @version $Id$
 */
public class FTPSession extends Session {
    private static Logger log = Logger.getLogger(FTPSession.class);

    static {
        SessionFactory.addFactory(Protocol.FTP, new Factory());
    }

    private static class Factory extends SessionFactory {
        protected Session create(Host h) {
            return new FTPSession(h);
        }
    }

    protected FTPClient FTP;
    protected FTPFileEntryParser parser;

    protected FTPSession(Host h) {
        super(h);
    }

    protected Path mount(String directory) throws IOException {
        final Path workdir = super.mount(directory);
        if(Preferences.instance().getBoolean("ftp.timezone.auto")) {
            if(null == host.getTimezone()) {
                // No custom timezone set
                final List<TimeZone> matches = this.calculateTimezone();
                for(TimeZone tz : matches) {
                    // Save in bookmark. User should have the option to choose from determined zones.
                    host.setTimezone(tz);
                    break;
                }
                if(!matches.isEmpty()) {
                    // Reset parser to use newly determined timezone
                    parser = null;
                }
            }
        }
        return workdir;
    }

    protected TimeZone getTimezone() throws IOException {
        if(null == host.getTimezone()) {
            return TimeZone.getTimeZone(
                    Preferences.instance().getProperty("ftp.timezone.default"));
        }
        return host.getTimezone();
    }

    private TimeZone tz;

    /**
     * @return
     * @throws IOException
     */
    protected FTPFileEntryParser getFileParser() throws IOException {
        try {
            if(!this.getTimezone().equals(this.tz)) {
                tz = this.getTimezone();
                log.info("Reset parser to timezone:" + tz);
                parser = null;
            }
            if(null == parser) {
                String system = null;
                try {
                    system = this.FTP.system();
                }
                catch(FTPException e) {
                    log.warn(this.host.getHostname() + " does not support the SYST command:" + e.getMessage());
                }
                parser = new FTPParserFactory().createFileEntryParser(system, tz);
                if(parser instanceof Configurable) {
                    // Configure with default configuration
                    ((Configurable) parser).configure(null);
                }
            }
            return parser;
        }
        catch(ParserInitializationException e) {
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Best guess of available timezones given the offset of the modification
     * date in the directory listing from the UTC timestamp returned from <code>MDTM</code>
     * if available. Result is error prone because of additional daylight saving offsets.
     */
    private List<TimeZone> calculateTimezone() throws IOException {
        try {
            // Determine the server offset from UTC
            final AttributedList<Path> list = this.workdir().childs();
            if(list.isEmpty()) {
                log.warn("Cannot determine timezone with empty directory listing");
                return Collections.emptyList();
            }
            for(Path test : list) {
                if(test.attributes.isFile()) {
                    // Read the modify fact which must be UTC
                    long utc = this.FTP.mdtm(test.getAbsolute());
                    // Subtract seconds
                    utc -= utc % 60000;
                    long local = test.attributes.getModificationDate();
                    if(-1 == local) {
                        log.warn("No modification date in directory listing to calculate timezone");
                        continue;
                    }
                    // Subtract seconds
                    local -= local % 60000;
                    long offset = local - utc;
                    log.info("Calculated UTC offset is " + offset + "ms");
                    final List<TimeZone> zones = new ArrayList<TimeZone>();
                    if(TimeZone.getTimeZone(Preferences.instance().getProperty("ftp.timezone.default")).getOffset(utc) == offset) {
                        log.info("Offset equals local timezone offset.");
                        zones.add(TimeZone.getTimeZone(Preferences.instance().getProperty("ftp.timezone.default")));
                        return zones;
                    }
                    // The offset should be the raw GMT offset without the daylight saving offset.
                    // However the determied offset *does* include daylight saving time and therefore
                    // the call to TimeZone#getAvailableIDs leads to errorneous results.
                    final String[] timezones = TimeZone.getAvailableIDs((int) offset);
                    for(String timezone : timezones) {
                        log.info("Matching timezone identifier:" + timezone);
                        final TimeZone match = TimeZone.getTimeZone(timezone);
                        log.info("Determined timezone:" + match);
                        zones.add(match);
                    }
                    if(zones.isEmpty()) {
                        log.warn("Failed to calculate timezone for offset:" + offset);
                        continue;
                    }
                    return zones;
                }
            }
        }
        catch(FTPException e) {
            log.warn("Failed to calculate timezone:" + e.getMessage());
        }
        log.warn("No file in directory listing to calculate timezone");
        return Collections.emptyList();
    }

    public void setStatListSupportedEnabled(boolean statListSupportedEnabled) {
        this.FTP.setStatListSupportedEnabled(statListSupportedEnabled);
    }

    public void setExtendedListEnabled(boolean extendedListEnabled) {
        this.FTP.setExtendedListEnabled(extendedListEnabled);
    }

    private Map<FTPFileEntryParser, Boolean> parsers = new HashMap<FTPFileEntryParser, Boolean>(1);

    /**
     * @param p
     * @return True if the parser will read the file permissions
     */
    protected boolean isPermissionSupported(final FTPFileEntryParser p) {
        FTPFileEntryParser delegate;
        if(p instanceof CompositeFileEntryParser) {
            // Get the actual parser
            delegate = ((CompositeFileEntryParser) p).getCachedFtpFileEntryParser();
            if(null == delegate) {
                log.warn("Composite FTP parser has no cached delegate yet");
                return false;
            }
        }
        else {
            // Not a composite parser
            delegate = p;
        }
        if(null == parsers.get(delegate)) {
            // Cache the value as it might get queried frequently
            parsers.put(delegate, delegate instanceof UnixFTPEntryParser
                    || delegate instanceof LaxUnixFTPEntryParser
                    || delegate instanceof NetwareFTPEntryParser
                    || delegate instanceof RumpusFTPEntryParser
            );
        }
        return parsers.get(delegate);
    }

    public String getIdentification() {
        StringBuffer info = new StringBuffer(super.getIdentification() + "\n");
        try {
            info.append(this.FTP.system()).append("\n");
        }
        catch(IOException e) {
            log.warn(this.host.getHostname() + " does not support the SYST command:" + e.getMessage());
        }
        return info.toString();
    }

    public boolean isConnected() {
        if(FTP != null) {
            return this.FTP.isConnected();
        }
        return false;
    }

    public void close() {
        try {
            if(this.isConnected()) {
                this.fireConnectionWillCloseEvent();
                FTP.quit();
            }
        }
        catch(FTPException e) {
            log.error("FTP Error: " + e.getMessage());
        }
        catch(IOException e) {
            log.error("IO Error: " + e.getMessage());
        }
        finally {
            this.fireConnectionDidCloseEvent();
        }
    }

    public void interrupt() {
        try {
            super.interrupt();
            if(null == this.FTP) {
                return;
            }
            this.fireConnectionWillCloseEvent();
            this.FTP.interrupt();
        }
        catch(IOException e) {
            log.error(e.getMessage());
        }
        finally {
            this.FTP = null;
            this.fireConnectionDidCloseEvent();
        }
    }

    public void check() throws IOException {
        try {
            super.check();
        }
        catch(FTPException e) {
            log.debug(e.getMessage());
            this.interrupt();
            this.connect();
        }
        catch(FTPNullReplyException e) {
            log.debug(e.getMessage());
            this.interrupt();
            this.connect();
        }
    }

    final protected FTPMessageListener messageListener = new FTPMessageListener() {
        public void logCommand(String cmd) {
            FTPSession.this.log(true, cmd);
        }

        public void logReply(String reply) {
            FTPSession.this.log(false, reply);
        }
    };

    protected FTPClient getClient() {
        return new FTPClient(this.getEncoding(), messageListener);
    }

    protected void connect() throws IOException, FTPException, ConnectionCanceledException, LoginCanceledException {
        if(this.isConnected()) {
            return;
        }
        this.fireConnectionWillOpenEvent();

        this.message(MessageFormat.format(NSBundle.localizedString("Opening {0} connection to {1}", "Status", ""),
                host.getProtocol().getName(), host.getHostname()));

        this.FTP = this.getClient();
        this.FTP.setTimeout(this.timeout());
        this.FTP.connect(host.getHostname(true), host.getPort());
        if(!this.isConnected()) {
            throw new ConnectionCanceledException();
        }
        this.FTP.setStrictReturnCodes(true);
        this.FTP.setConnectMode(this.getConnectMode());
        this.message(MessageFormat.format(NSBundle.localizedString("{0} connection opened", "Status", ""),
                host.getProtocol().getName()));
        this.login();
        this.fireConnectionDidOpenEvent();
        if("UTF-8".equals(this.getEncoding())) {
            this.FTP.utf8();
        }
    }

    /**
     * @return The custom encoding specified in the host of this session
     *         or the default encoding if no cusdtom encoding is set
     * @see Preferences
     * @see Host
     */
    protected FTPConnectMode getConnectMode() {
        if(null == this.host.getFTPConnectMode()) {
            if(Proxy.usePassiveFTP()) {
                return FTPConnectMode.PASV;
            }
            return FTPConnectMode.ACTIVE;
        }
        return this.host.getFTPConnectMode();

    }

    protected void login(final Credentials credentials) throws IOException {
        try {
            this.FTP.login(credentials.getUsername(), credentials.getPassword());
            this.message(NSBundle.localizedString("Login successful", "Credentials", ""));
        }
        catch(FTPException e) {
            this.message(NSBundle.localizedString("Login failed", "Credentials", ""));
            this.login.fail(host, e.getMessage());
            this.login();
        }
    }

    public Path workdir() throws IOException {
        if(!this.isConnected()) {
            throw new ConnectionCanceledException();
        }
        if(null == workdir) {
            workdir = PathFactory.createPath(this, this.FTP.pwd(), Path.DIRECTORY_TYPE);
            if(workdir.isRoot()) {
                workdir.attributes.setType(Path.VOLUME_TYPE | Path.DIRECTORY_TYPE);
            }
        }
        return workdir;
    }

    protected void setWorkdir(Path workdir) throws IOException {
        if(workdir.equals(this.workdir)) {
            // Do not attempt to change the workdir if the same
            return;
        }
        if(!this.isConnected()) {
            throw new ConnectionCanceledException();
        }
        this.FTP.chdir(workdir.getAbsolute());
        // Workdir change succeeded
        this.workdir = workdir;
    }

    protected void noop() throws IOException {
        if(this.isConnected()) {
            this.FTP.noop();
        }
    }

    public boolean isSendCommandSupported() {
        return true;
    }

    public void sendCommand(String command) throws IOException {
        if(this.isConnected()) {
            this.message(command);

            this.FTP.quote(command);
        }
    }
}