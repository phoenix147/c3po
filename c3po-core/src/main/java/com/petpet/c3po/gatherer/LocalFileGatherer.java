package com.petpet.c3po.gatherer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs.FileContent;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.VFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.petpet.c3po.api.gatherer.MetaDataGatherer;
import com.petpet.c3po.api.model.helper.MetadataStream;
import com.petpet.c3po.common.Constants;

/**
 * A gatherer of a local file system. It is a {@link Runnable} class that reads
 * the meta data files into memory and stores them into a processing queue.
 * 
 * @author Petar Petrov <me@petarpetrov.org>
 * 
 */
public class LocalFileGatherer implements MetaDataGatherer {

  /**
   * A default logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(LocalFileGatherer.class);

  /**
   * The configuration of the gatherer.
   */
  private Map<String, String> config;

  /**
   * A queue where the {@link MetadataStream} objects are stored.
   */
  private final Queue<MetadataStream> queue;

  /**
   * The count of objects gathered.
   */
  private long count;

  /**
   * A flag denoting whether this gatherer is ready traversing the file system.
   */
  private boolean ready;

  /**
   * A lock for synchronization with other workers.
   */
  private Object lock;

  /**
   * Creates a new gatherer.
   */
  public LocalFileGatherer() {
    this.queue = new LinkedList<MetadataStream>();
    this.ready = false;
  }

  /**
   * Creates a new gatherer with the given config.
   * 
   * @param config
   */
  public LocalFileGatherer(Map<String, String> config) {
    this();
    this.config = config;
  }

  /**
   * Creates a new gatherer with the given object lock.
   * 
   * @param lock
   */
  public LocalFileGatherer(Object lock) {
    this();
    this.lock = lock;
  }

  /**
   * Runs this gatherer and traverses the file system (optionally in a recursive
   * fashion) . Once the files are gathered, all waiting threads on the locks
   * monitor are notified.
   */
  @Override
  public synchronized void run() {
    String path = this.config.get( Constants.OPT_COLLECTION_LOCATION );
    boolean recursive = Boolean.valueOf( this.config.get( Constants.OPT_RECURSIVE ) );

    this.ready = false;
    this.traverseFiles(new File(path), recursive, true);
    System.out.println(this.count + " files were gathered successfully");
    LOG.info("{} files were gathered successfully", this.count);
    this.ready = true;

    synchronized ( lock ) {
      this.lock.notifyAll();

    }
  }

  /**
   * {@inheritDoc}
   */
  public MetadataStream getNext() {
    synchronized ( lock ) {
      return queue.poll();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setConfig(Map<String, String> config) {
    this.config = config;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasNext() {
    return !this.queue.isEmpty();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isReady() {
    return this.ready;
  }

  /**
   * Reads the given input stream into memory and returns it. The stream is
   * closed.
   * 
   * @param name
   *          the name of the file/object holding the stream.
   * @param data
   *          the input stream to read.
   * @return the string that was read out of the stream.
   */
  private String readStream(String name, InputStream data) {
    String result = null;
    try {
      result = IOUtils.toString( data );
    } catch ( IOException e ) {
      LOG.warn( "An error occurred, while reading the stream for {}: {}", name, e.getMessage() );
    } finally {
      IOUtils.closeQuietly( data );
    }
    return result;
  }

  /**
   * Traverses the file system starting from the given file. If the recursive
   * flag is true, then the traversal is recursive.
   * 
   * @param file
   *          the directory to traverse.
   * @param recursive
   *          whether or not to do it recursively.
   * @param firstLevel
   *          denotes whether this is the first level of traversal.
   */
  private void traverseFiles(File file, boolean recursive, boolean firstLevel) {

    if ( file.isDirectory() && (recursive || firstLevel) ) {

      File[] files = file.listFiles();
      for ( File f : files ) {
        traverseFiles( f, recursive, false );
      }
    } else {
      String filePath = file.getAbsolutePath();

      if ( isArchive( filePath ) ) {

        processArchive( filePath );

      } else {

        processFile( filePath );

      }
    }

      if ((this.count % 1000) == 0) {
        LOG.info("traversed: {} files", this.count);
        synchronized (lock) {
          this.lock.notify();

        }
      }

      if (this.queue.size() > 10000 && this.count % 1000 == 0) {
        synchronized (lock) {
          this.lock.notifyAll();
        }
      }
      
      if (this.count % 10000 == 0) {
        System.out.println(this.count + " files were processed");
      }

      
  }

  private void traverseArchive( String filePath, FileObject file ) {
    try {
      FileObject[] children = file.getChildren();
      for ( FileObject child : children ) {
        if ( child.getType() == FileType.FOLDER ) {
          this.traverseArchive( filePath, child );

        } else {
          String name = child.getName().toString();
          FileContent fc = child.getContent();
          InputStream zis = fc.getInputStream();
          String data = this.readStream( name, zis );
          submitMetadataResult( new MetadataStream( name, data ) );
        }
      }
    } catch ( FileSystemException e ) {
      LOG.warn( "Could not resolve file: {}", e.getMessage() );
    }

  }

  private String getPrefix( String filePath ) {
    String prefix = filePath.substring( filePath.lastIndexOf( '.' ) + 1 );
    prefix = (prefix.length() > 4) ? "file://" : prefix + "://";

    if ( filePath.endsWith( ".tar.gz" ) ) {
      prefix = "tgz://";
    }

    return prefix;
  }

  private boolean isArchive( String name ) {
    return name.endsWith( ".zip" ) || name.endsWith( ".bzip2" ) || name.endsWith( ".bz2" ) || name.endsWith( ".gzip" )
        || name.endsWith( ".gz" ) || name.endsWith( ".jar" ) || name.endsWith( ".tar" ) || name.endsWith( ".tar.gz" )
        || name.endsWith( ".tgz" ) || name.endsWith( ".pack" ) || name.endsWith( ".xz" );
  }

  private InputStream getInputStream( String filePath ) {
    try {
      return new BufferedInputStream( new FileInputStream( new File( filePath ) ), 8192 );
    } catch ( FileNotFoundException e ) {
      LOG.warn( "File not found: {}. {}", filePath, e.getMessage() );
      return null;
    }
  }

  private void processFile( String filePath ) {
    InputStream is = this.getInputStream( filePath );
    String data = readStream( filePath, is );
    submitMetadataResult( new MetadataStream( filePath, data ) );

  }

  private void processArchive( String filePath ) {
    try {
      FileSystemManager fsManager = VFS.getManager();
      String prefix = this.getPrefix( filePath );
      FileObject archive = fsManager.resolveFile( prefix + filePath );

      traverseArchive( filePath, archive );

    } catch ( FileSystemException e ) {
      LOG.warn( "Could not resolve file: {}", e.getMessage() );
    }
  }

  private void submitMetadataResult( MetadataStream ms ) {
    this.queue.add( ms );
    count++;
  }
}
