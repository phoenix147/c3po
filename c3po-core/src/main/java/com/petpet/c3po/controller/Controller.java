package com.petpet.c3po.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.petpet.c3po.adaptor.fits.FITSDigesterAdaptor;
import com.petpet.c3po.api.dao.Cache;
import com.petpet.c3po.api.dao.PersistenceLayer;
import com.petpet.c3po.datamodel.Element;
import com.petpet.c3po.gatherer.FileSystemGatherer;

public class Controller {

  private static final Logger LOGGER = LoggerFactory.getLogger(Controller.class);
  private PersistenceLayer persistence;
  private ExecutorService pool;
  private FileSystemGatherer gatherer;
  private int counter = 0;

  public Controller(PersistenceLayer pLayer) {
    this.persistence = pLayer;
    
  }

  public void collect(Map<String, String> config) {

    this.gatherer = new FileSystemGatherer(config);
    LOGGER.info("{} files to be processed", gatherer.getCount());
    
    // if (gatherer != null) {
    // LOGGER.info("Found matching gatherer of type, starting...");
    // 
    //
    this.startJobs(Integer.parseInt(config.get("config.threads")));

    // if (gatherer.getCount() > 100) {
    // int counter = 10;
    // List<String> next = gatherer.getNext(10);
    //
    // while (!next.isEmpty()) {
    // LOGGER.debug("processing next {} files", next.size());
    //
    // this.dispatch(next);
    // next = gatherer.getNext(10);
    // counter += 10;
    //
    // if (counter % 500 == 0) {
    // // cleanUp();
    // LOGGER.info("Finished processing {} files", counter);
    // }
    // }
    //
    // } else {
    // List<String> all = gatherer.getAll();
    // this.dispatch(all);
    // // cleanUp();
    // }
    // }

    
  }

  private void startJobs(int threads) {
    this.pool = Executors.newFixedThreadPool(threads);
    
    for (int i = 0; i < threads; i++) {
      FITSDigesterAdaptor f = new FITSDigesterAdaptor(this);
      this.pool.submit(f);
    }

    this.pool.shutdown();

    try {
      //What happens if the time out occurrs first?
      this.pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public synchronized void processElement(Element e) {
    DBCollection elements = this.persistence.getDB().getCollection("elements");
    BasicDBObject document = e.getDocument();
    elements.insert(document);

  }

  public Cache getCache() {
    return this.persistence.getCache();
  }

  public synchronized String getNext() {
    List<String> next = this.gatherer.getNext(1);
    String result = null;
    if (next.isEmpty()) {
      LOGGER.info("Gathering process finished");
      
    } else {
      result = next.get(0);
    }
    
    this.counter++;
    
    if (counter % 500 == 0) {
      LOGGER.info("Finished processing {} files", counter);
    }
    
    return result;
  }

}
