package org.hisp.dhis.hibernate;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.cfg.Configuration;
import org.hisp.dhis.commons.util.SystemUtils;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.external.location.LocationManagerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import static org.hibernate.cfg.AvailableSettings.*;

/**
 * @author Torgeir Lorange Ostby
 */
@Slf4j
public class DefaultHibernateConfigurationProvider
    implements HibernateConfigurationProvider
{
    private Configuration configuration = null;

    private static final String MAPPING_RESOURCES_ROOT = "org/hisp/dhis/";

    private static final String FILENAME_CACHE_NAMES = "hibernate-caches.txt";

    private static final String PROP_EHCACHE_PEER_PROVIDER_RIM_URLS = "ehcache.peer.provider.rmi.urls";

    private static final String PROP_EHCACHE_PEER_LISTENER_HOSTNAME = "ehcache.peer.listener.hostname";

    private static final String PROP_EHCACHE_PEER_LISTENER_PORT = "ehcache.peer.listener.port";

    private static final String PROP_EHCACHE_PEER_LISTENER_REMOTE_OBJECT_PORT = "ehcache.peer.listener.remote.object.port";

    private static final String FILENAME_EHCACHE_REPLICATION = "/ehcache-replication.xml";

    @Autowired
    private org.springframework.core.env.Environment environment;

    // -------------------------------------------------------------------------
    // Property resources
    // -------------------------------------------------------------------------

    private String defaultPropertiesFile = "hibernate-default.properties";

    private List<Resource> jarResources = new ArrayList<>();

    private List<Resource> dirResources = new ArrayList<>();

    private List<String> clusterHostnames = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private DhisConfigurationProvider configProvider;

    public void setConfigProvider( DhisConfigurationProvider configProvider )
    {
        this.configProvider = configProvider;
    }

    // -------------------------------------------------------------------------
    // Initialize
    // -------------------------------------------------------------------------

    @PostConstruct
    public void init()
        throws Exception
    {
        Configuration config = new Configuration();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        // ---------------------------------------------------------------------
        // Add mapping resources
        // ---------------------------------------------------------------------

        Enumeration<URL> resources = classLoader.getResources( MAPPING_RESOURCES_ROOT );

        while ( resources.hasMoreElements() )
        {
            URL resource = resources.nextElement();

            if ( ResourceUtils.isJarURL( resource ) )
            {
                URL jarFile = ResourceUtils.extractJarFileURL( resource );

                File file = ResourceUtils.getFile( jarFile );

                jarResources.add( new FileSystemResource( file.getAbsolutePath() ) );

                log.debug(
                    String.format( "Adding jar in which to search for hbm.xml files: %s", file.getAbsolutePath() ) );

                config.addJar( file );
            }
            else
            {
                File file = ResourceUtils.getFile( resource );

                dirResources.add( new FileSystemResource( file ) );

                log.debug( String
                    .format( "Adding directory in which to search for hbm.xml files: %s", file.getAbsolutePath() ) );

                config.addDirectory( file );
            }
        }

        // ---------------------------------------------------------------------
        // Add default properties from class path
        // ---------------------------------------------------------------------
        Properties defaultProperties = getProperties( defaultPropertiesFile );

        config.addProperties( defaultProperties );

        // ---------------------------------------------------------------------
        // Add custom properties from file system
        // ---------------------------------------------------------------------
        try
        {
            Properties fileHibernateProperties = getHibernateProperties();

            config.addProperties( fileHibernateProperties );
        }
        catch ( LocationManagerException ex )
        {
            log.info( "Could not read external configuration from file system" );
        }

        // ---------------------------------------------------------------------
        // Handle cache replication
        // ---------------------------------------------------------------------
        if ( configProvider.isClusterEnabled() )
        {
            config.setProperty( "net.sf.ehcache.configurationResourceName", FILENAME_EHCACHE_REPLICATION );

            setCacheReplicationConfigSystemProperties();

            log.info( "Clustering and cache replication enabled" );
        }

        log.info( String.format(
            "Hibernate configuration loaded: dialect: '%s', region factory: '%s', connection pool max size: %s",
            config.getProperty( DIALECT ), config.getProperty( CACHE_REGION_FACTORY ),
            config.getProperty( C3P0_MAX_SIZE ) ) );

        this.configuration = config;
    }

    // -------------------------------------------------------------------------
    // HibernateConfigurationProvider implementation
    // -------------------------------------------------------------------------

    @Override
    public Configuration getConfiguration()
    {
        return configuration;
    }

    @Override
    public List<Resource> getJarResources()
    {
        return jarResources;
    }

    @Override
    public List<Resource> getDirectoryResources()
    {
        return dirResources;
    }

    @Override
    public List<String> getClusterHostnames()
    {
        return clusterHostnames;
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private Properties getHibernateProperties()
    {
        Properties p = new Properties();

//        set( "hibernate.id.disable_delayed_identity_inserts", "true", p );
        set( JDBC_TYLE_PARAMS_ZERO_BASE, "true", p );
        set( TABLE_GENERATOR_STORE_LAST_USED, "true", p );

        set( DIALECT, configProvider.getProperty( ConfigurationKey.CONNECTION_DIALECT ), p );
        set( DRIVER, configProvider.getProperty( ConfigurationKey.CONNECTION_DRIVER_CLASS ), p );
        set( URL, configProvider.getProperty( ConfigurationKey.CONNECTION_URL ), p );
        set( USER, configProvider.getProperty( ConfigurationKey.CONNECTION_USERNAME ), p );
        set( PASS, configProvider.getProperty( ConfigurationKey.CONNECTION_PASSWORD ), p );
        set( C3P0_MAX_SIZE, configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_MAX_SIZE ), p );
        set( ConfigurationKey.CONNECTION_POOL_MIN_SIZE.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_MIN_SIZE ), p );
        set( ConfigurationKey.CONNECTION_POOL_INITIAL_SIZE.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_INITIAL_SIZE ), p );
        set( ConfigurationKey.CONNECTION_POOL_ACQUIRE_INCR.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_ACQUIRE_INCR ), p );
        set( ConfigurationKey.CONNECTION_POOL_MAX_IDLE_TIME.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_MAX_IDLE_TIME ), p );
        set( ConfigurationKey.CONNECTION_POOL_MAX_IDLE_TIME_EXCESS_CON.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_MAX_IDLE_TIME_EXCESS_CON ), p );
        set( ConfigurationKey.CONNECTION_POOL_IDLE_CON_TEST_PERIOD.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_IDLE_CON_TEST_PERIOD ), p );
        set( ConfigurationKey.CONNECTION_POOL_TEST_ON_CHECKIN.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_TEST_ON_CHECKIN ), p );
        set( ConfigurationKey.CONNECTION_POOL_TEST_ON_CHECKOUT.getKey(),
            configProvider.getProperty( ConfigurationKey.CONNECTION_POOL_TEST_ON_CHECKOUT ), p );
        set( ConfigurationKey.ENCRYPTION_PASSWORD.getKey(),
            configProvider.getProperty( ConfigurationKey.ENCRYPTION_PASSWORD ), p );

        if ( SystemUtils.isTestRun( environment.getActiveProfiles() ) )
        {
            set( HBM2DDL_AUTO, configProvider.getProperty( ConfigurationKey.CONNECTION_SCHEMA ), p );
            set( USE_SECOND_LEVEL_CACHE, "false", p );
            set( USE_QUERY_CACHE, "false", p );
        }

        // Enable Hibernate statistics if Hibernate Monitoring is enabled
        if ( configProvider.isEnabled( ConfigurationKey.MONITORING_HIBERNATE_ENABLED ) )
        {
            p.put( GENERATE_STATISTICS, true );
        }

        return p;
    }

    private void set( String key, String value, Properties props )
    {
        if ( value != null && !value.isEmpty() )
        {
            props.put( key, value );
        }
    }

    private Properties getProperties( String path )
        throws IOException
    {
        try
        {
            return getProperties( new ClassPathResource( path ).getInputStream() );
        }
        catch ( FileNotFoundException ex )
        {
            return null;
        }
        catch ( SecurityException ex )
        {
            log.warn( "Not permitted to read properties file: " + path, ex );

            return null;
        }
    }

    private Properties getProperties( InputStream inputStream )
        throws IOException
    {
        try
        {
            Properties properties = new Properties();
            properties.load( inputStream );

            return properties;
        }
        finally
        {
            inputStream.close();
        }
    }

    /**
     * Sets system properties to be resolved in the Ehcache cache replication
     * configuration.
     */
    private void setCacheReplicationConfigSystemProperties()
    {
        String instanceHost = configProvider.getProperty( ConfigurationKey.CLUSTER_HOSTNAME );
        String instancePort = configProvider.getProperty( ConfigurationKey.CLUSTER_CACHE_PORT );
        String remoteObjectPort = configProvider.getProperty( ConfigurationKey.CLUSTER_CACHE_REMOTE_OBJECT_PORT );
        String clusterMembers = configProvider.getProperty( ConfigurationKey.CLUSTER_MEMBERS );

        // Split using comma delimiter along with possible spaces in between

        String[] clusterMemberList = clusterMembers.trim().split( "\\s*,\\s*" );

        List<String> cacheNames = getCacheNames();

        final StringBuilder rmiUrlBuilder = new StringBuilder();

        for ( String member : clusterMemberList )
        {
            final String clusterUrl = "//" + member + "/";

            cacheNames.forEach( name -> rmiUrlBuilder.append( clusterUrl ).append( name ).append( "|" ) );

            clusterHostnames.add( member );

            log.info( "Found cluster instance: " + member );
        }

        String rmiUrls = StringUtils.removeEnd( rmiUrlBuilder.toString(), "|" );

        if ( StringUtils.isBlank( rmiUrls ) )
        {
            log.warn( "At least one cluster instance must be specified when clustering is enabled" );
        }

        System.setProperty( PROP_EHCACHE_PEER_LISTENER_HOSTNAME, instanceHost );
        System.setProperty( PROP_EHCACHE_PEER_LISTENER_PORT, instancePort );
        System.setProperty( PROP_EHCACHE_PEER_PROVIDER_RIM_URLS, rmiUrls );
        System.setProperty( PROP_EHCACHE_PEER_LISTENER_REMOTE_OBJECT_PORT, remoteObjectPort );

        log.info( "Ehcache config properties: " + instanceHost + ", " + instancePort + ", " + rmiUrls + ", " +
            remoteObjectPort );
    }

    /**
     * Returns a list of names of all Hibernate caches.
     */
    private List<String> getCacheNames()
    {
        try (InputStream input = new ClassPathResource( FILENAME_CACHE_NAMES ).getInputStream())
        {
            return IOUtils.readLines( input, StandardCharsets.UTF_8 );
        }
        catch ( IOException ex )
        {
            throw new UncheckedIOException( ex );
        }
    }
}
