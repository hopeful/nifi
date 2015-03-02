/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.provenance.journaling.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortField.Type;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.nifi.provenance.SearchableFields;
import org.apache.nifi.provenance.journaling.JournaledStorageLocation;
import org.apache.nifi.provenance.search.Query;

public class LuceneIndexSearcher implements EventIndexSearcher {
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final FSDirectory fsDirectory;
    
    private final String description;
    
    public LuceneIndexSearcher(final File indexDirectory) throws IOException {
        this.fsDirectory = FSDirectory.open(indexDirectory);
        this.reader = DirectoryReader.open(fsDirectory);
        this.searcher = new IndexSearcher(reader);
        this.description = "LuceneIndexSearcher[indexDirectory=" + indexDirectory + "]";
    }
    
    public LuceneIndexSearcher(final DirectoryReader reader, final File indexDirectory) {
        this.reader = reader;
        this.searcher = new IndexSearcher(reader);
        this.fsDirectory = null;
        this.description = "LuceneIndexSearcher[indexDirectory=" + indexDirectory + "]";
    }
    
    @Override
    public void close() throws IOException {
        IOException suppressed = null;
        try {
            reader.close();
        } catch (final IOException ioe) {
            suppressed = ioe;
        }
        
        if ( fsDirectory != null ) {
            fsDirectory.close();
        }
        
        if ( suppressed != null ) {
            throw suppressed;
        }
    }
    
    private JournaledStorageLocation createLocation(final Document document) {
        final String containerName = document.get(IndexedFieldNames.CONTAINER_NAME);
        final String sectionName = document.get(IndexedFieldNames.SECTION_NAME);
        final long journalId = document.getField(IndexedFieldNames.JOURNAL_ID).numericValue().longValue();
        final int blockIndex = document.getField(IndexedFieldNames.BLOCK_INDEX).numericValue().intValue();
        final long eventId = document.getField(IndexedFieldNames.EVENT_ID).numericValue().longValue();
        
        return new JournaledStorageLocation(containerName, sectionName, journalId, blockIndex, eventId);
    }
    
    private List<JournaledStorageLocation> getOrderedLocations(final TopDocs topDocs) throws IOException {
        final ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        final List<JournaledStorageLocation> locations = new ArrayList<>(scoreDocs.length);
        populateLocations(topDocs, locations);
        
        return locations;
    }
    
    
    private void populateLocations(final TopDocs topDocs, final Collection<JournaledStorageLocation> locations) throws IOException {
        for ( final ScoreDoc scoreDoc : topDocs.scoreDocs ) {
            final Document document = reader.document(scoreDoc.doc);
            locations.add(createLocation(document));
        }
    }
    
    
    @Override
    public SearchResult search(final Query provenanceQuery) throws IOException {
        final org.apache.lucene.search.Query luceneQuery = QueryUtils.convertQueryToLucene(provenanceQuery);
        final TopDocs topDocs = searcher.search(luceneQuery, provenanceQuery.getMaxResults());
        final List<JournaledStorageLocation> locations = getOrderedLocations(topDocs);
        
        return new SearchResult(locations, topDocs.totalHits);
    }

    @Override
    public List<JournaledStorageLocation> getEvents(final long minEventId, final int maxResults) throws IOException {
        final BooleanQuery query = new BooleanQuery();
        query.add(NumericRangeQuery.newLongRange(IndexedFieldNames.EVENT_ID, minEventId, null, true, true), Occur.MUST);
        
        final TopDocs topDocs = searcher.search(query, maxResults, new Sort(new SortField(IndexedFieldNames.EVENT_ID, Type.LONG)));
        return getOrderedLocations(topDocs);
    }

    @Override
    public Long getMaxEventId(final String container, final String section) throws IOException {
        final BooleanQuery query = new BooleanQuery();
        
        if ( container != null ) {
            query.add(new TermQuery(new Term(IndexedFieldNames.CONTAINER_NAME, container)), Occur.MUST);
        }
        
        if ( section != null ) {
            query.add(new TermQuery(new Term(IndexedFieldNames.SECTION_NAME, section)), Occur.MUST);
        }
        
        final TopDocs topDocs = searcher.search(query, 1, new Sort(new SortField(IndexedFieldNames.EVENT_ID, Type.LONG, true)));
        final List<JournaledStorageLocation> locations = getOrderedLocations(topDocs);
        if ( locations.isEmpty() ) {
            return null;
        }
        
        return locations.get(0).getEventId();
    }

    @Override
    public List<JournaledStorageLocation> getEventsForFlowFiles(final Collection<String> flowFileUuids, final long earliestTime, final long latestTime) throws IOException {
        // Create a query for all Events related to the FlowFiles of interest. We do this by adding all ID's as
        // "SHOULD" clauses and then setting the minimum required to 1.
        final BooleanQuery flowFileIdQuery;
        if (flowFileUuids == null || flowFileUuids.isEmpty()) {
            flowFileIdQuery = null;
        } else {
            flowFileIdQuery = new BooleanQuery();
            for (final String flowFileUuid : flowFileUuids) {
                flowFileIdQuery.add(new TermQuery(new Term(SearchableFields.FlowFileUUID.getSearchableFieldName(), flowFileUuid)), Occur.SHOULD);
            }
            flowFileIdQuery.setMinimumNumberShouldMatch(1);
        }
        
        flowFileIdQuery.add(NumericRangeQuery.newLongRange(SearchableFields.EventTime.getSearchableFieldName(), 
                earliestTime, latestTime, true, true), Occur.MUST);
        
        final TopDocs topDocs = searcher.search(flowFileIdQuery, 1000);
        return getOrderedLocations(topDocs);
    }
    
    
    @Override
    public List<JournaledStorageLocation> getLatestEvents(final int numEvents) throws IOException {
        final MatchAllDocsQuery query = new MatchAllDocsQuery();
        
        final TopFieldDocs topDocs = searcher.search(query, numEvents, new Sort(new SortField(IndexedFieldNames.EVENT_ID, Type.LONG, true)));
        final List<JournaledStorageLocation> locations = getOrderedLocations(topDocs);
        return locations;
    }
    
    @Override
    public String toString() {
        return description;
    }

    @Override
    public long getNumberOfEvents() {
        return reader.numDocs();
    }
}