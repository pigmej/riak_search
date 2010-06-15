/*
%% -------------------------------------------------------------------
%%
%% Copyright (c) 2007-2010 Basho Technologies, Inc.  All Rights Reserved.
%%
%% This file is provided to you under the Apache License,
%% Version 2.0 (the "License"); you may not use this file
%% except in compliance with the License.  You may obtain
%% a copy of the License at
%%
%%   http://www.apache.org/licenses/LICENSE-2.0
%%
%% Unless required by applicable law or agreed to in writing,
%% software distributed under the License is distributed on an
%% "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
%% KIND, either express or implied.  See the License for the
%% specific language governing permissions and limitations
%% under the License.
%%
%% -------------------------------------------------------------------
%%
%% J. Muellerleile
%%
*/

package raptor.store;

import java.lang.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.*;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NativeFSLockFactory;
import org.apache.lucene.util.Version;

import org.json.*;
import org.apache.log4j.Logger;

import raptor.util.*;
import raptor.store.handlers.*;

public class LuceneStore {
    final private static Logger log = Logger.getLogger(LuceneStore.class);
    final private static int COMMIT_COUNT = 1000;
    final private static int LUCENE_MERGE_FACTOR = 10;
    final private static boolean IDX_TRACE = false;
    final private static int MAX_RESULTS = 50000; // todo: configurable

    private IndexWriter luceneWriter;
    private IndexReader luceneReader;
    private Searcher searcher;
    final private NIOFSDirectory luceneDirectory;
    final private static Lock luceneLock = new ReentrantLock();
    
    private static boolean test = true;
    
    @SuppressWarnings("deprecation")
    public LuceneStore(String directory) throws Exception {
        File luceneFS = RaptorUtils.ensureDirectory(directory);
        luceneDirectory =
                new NIOFSDirectory(luceneFS);
        luceneDirectory.setLockFactory(
                new NativeFSLockFactory("/tmp")); // todo: configurable
        luceneWriter = new IndexWriter(
                luceneDirectory,
                new WhitespaceAnalyzer(),
                IndexWriter.MaxFieldLength.LIMITED);
        luceneWriter.setUseCompoundFile(false);
        luceneWriter.setRAMBufferSizeMB(500.0);
        luceneWriter.setMergeFactor(LUCENE_MERGE_FACTOR); // todo: config
        if (IDX_TRACE) luceneWriter.setInfoStream(System.out); // todo: config
        luceneReader = luceneWriter.getReader();
        searcher = new IndexSearcher(luceneReader);
    }

    public void addDocument(Document doc) throws Exception {
        luceneLock.lock();
        try {
            luceneWriter.addDocument(doc);
        } finally {
            luceneLock.unlock();
        }
    }
    
    public void close() throws Exception {
        if (luceneLock.tryLock(120, TimeUnit.SECONDS)) {
            try {
                luceneWriter.commit();
                searcher.close();
                luceneReader.close();
                luceneWriter.close();
            } finally {
                luceneLock.unlock();
            }
        } else {
            throw new Exception("close: timed out (2 minutes)");
        }
    }

    public void sync() throws Exception {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    if (luceneLock.tryLock(1, TimeUnit.SECONDS)) {
                        try {
                            luceneWriter.waitForMerges();
                            luceneWriter.commit();
                            //luceneWriter.optimize(10);
                            
                            searcher.close();
                            luceneReader.close();
                            luceneReader = luceneWriter.getReader();
                            searcher = new IndexSearcher(luceneReader);
                            
                        } catch (Exception ex) {
                            log.info("* lucene.sync(): exception: ");
                            ex.printStackTrace();
                        } finally {
                            luceneLock.unlock();
                        }
                    } else {
                        log.info("* lucene.sync(): lock timeout.");
                    }
                } catch (java.lang.InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        });
        t.start();
    }

    public void optimize(int segment_count) throws Exception {
        log.info("optimize(" + segment_count + ")");
        luceneLock.lock();
        try {
            luceneWriter.optimize(segment_count, false);
        } finally {
            luceneLock.unlock();
        }
        sync();
    }

    public boolean documentsExist(String query)
            throws Exception {
            Analyzer analyzer = new WhitespaceAnalyzer();
            QueryParser qp = new QueryParser(Version.LUCENE_CURRENT,
                    "bucket", analyzer);
            qp.setAllowLeadingWildcard(true);
            Query l_query = qp.parse(query);
            Filter filter = new QueryWrapperFilter(l_query);
            return documentsExist(filter);
    }

    protected boolean documentsExist(Filter f1) throws Exception {
        TopDocs hits;
        Filter f = new CachingWrapperFilter(f1);
        luceneLock.lock();
        try {
            hits = searcher.search(new org.apache.lucene.search.MatchAllDocsQuery(), f, 1);
        } finally {
            luceneLock.unlock();
        }
        return hits.scoreDocs.length > 0;
    }
    
    public List<Document> query(String query)
        throws Exception {
        return query(query, 0);
    }
    
    public void query(String query, 
                      final ResultHandler resultHandler)
        throws Exception {
        Analyzer analyzer = new WhitespaceAnalyzer();
        QueryParser qp = new QueryParser(Version.LUCENE_CURRENT,
                "index_field", analyzer);
        qp.setAllowLeadingWildcard(true);
        Query l_query = qp.parse(query);
        Filter l_filter = new CachingWrapperFilter(new QueryWrapperFilter(l_query));
        
        luceneLock.lock();
        try {
            searcher.search(
                new MatchAllDocsQuery(), 
                l_filter, 
                new Collector() {
                    private int docBase;
                    private IndexReader reader;
                    public void setScorer(Scorer scorer) { }
                    public boolean acceptsDocsOutOfOrder() { return true; }
                    public void collect(int docn) {
                        try {
                            Document doc = reader.document(docn);
                            JSONObject jo = new JSONObject();
                            List<Fieldable> fields = doc.getFields();
                            for(Fieldable field: fields) {
                                jo.put(field.name(), field.stringValue());
                            }
                            resultHandler.handleCatalogResult(jo);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    public void setNextReader(IndexReader reader, int docBase) {
                        this.docBase = docBase;
                        this.reader = reader;
                    }
                }
            );
        } finally {
            luceneLock.unlock();
        }
    }
    
    @SuppressWarnings("deprecation")
    public List<Document> query(String query, int maxResults)
            throws Exception {
        List<Document> matches = new ArrayList<Document>();
        Analyzer analyzer = new WhitespaceAnalyzer();
        QueryParser qp = new QueryParser(Version.LUCENE_CURRENT,
                "index_field", analyzer);
        qp.setAllowLeadingWildcard(true);
        Query l_query = qp.parse(query);
        Filter l_filter = new CachingWrapperFilter(new QueryWrapperFilter(l_query));
        
        luceneLock.lock();
        try {
            TopDocs hits;
            if (maxResults == 0) {
                hits = searcher.search(new MatchAllDocsQuery(), l_filter, Integer.MAX_VALUE-1);
            } else {
                hits = searcher.search(new MatchAllDocsQuery(), l_filter, MAX_RESULTS);
            }
            for (int i = 0; i < hits.scoreDocs.length; i++) {
                int docId = hits.scoreDocs[i].doc;
                Document d = searcher.doc(docId);
                matches.add(d);
            }
        } finally {
            luceneLock.unlock();
        }
        return matches;
    }
}
