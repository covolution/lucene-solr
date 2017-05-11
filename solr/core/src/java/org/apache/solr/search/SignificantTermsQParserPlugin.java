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

package org.apache.solr.search;


import java.io.IOException;
import java.util.TreeSet;
import java.util.List;
import java.util.ArrayList;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;

public class SignificantTermsQParserPlugin extends QParserPlugin {

  public static final String NAME = "sigificantTerms";

  @Override
  public QParser createParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new SignifcantTermsQParser(qstr, localParams, params, req);
  }

  private static class SignifcantTermsQParser extends QParser {

    public SignifcantTermsQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
      super(qstr, localParams, params, req);
    }

    @Override
    public Query parse() throws SyntaxError {
      String field = getParam("field");
      int numTerms = Integer.parseInt(params.get("numTerms", "20"));
      float minDocs = Float.parseFloat(params.get("minDocFreq", "5"));
      float maxDocs = Float.parseFloat(params.get("maxDocFreq", ".3"));
      int minTermLength = Integer.parseInt(params.get("minTermLength", "4"));
      String backgroundQ = params.get("backgroundQuery", null);

      return new SignificantTermsQuery(field, numTerms, minDocs, maxDocs, minTermLength, backgroundQ);
    }
  }

  private static class SignificantTermsQuery extends AnalyticsQuery {

    private String field;
    private int numTerms;
    private float maxDocs;
    private float minDocs;
    private int minTermLength;
    private String backgroundQuery;

    public SignificantTermsQuery(String field, int numTerms, float minDocs, float maxDocs, int minTermLength, String backgroundQuery) {
      this.field = field;
      this.numTerms = numTerms;
      this.minDocs = minDocs;
      this.maxDocs = maxDocs;
      this.minTermLength = minTermLength;
      this.backgroundQuery = backgroundQuery;
    }

    @Override
    public DelegatingCollector getAnalyticsCollector(ResponseBuilder rb, IndexSearcher searcher) {

      BitSet backgroundSet =  null;
      if (backgroundQuery != null) {
        SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
        LocalSolrQueryRequest otherReq = new LocalSolrQueryRequest(info.getReq().getCore(), new ModifiableSolrParams());
        try {
          QParser parser = QParser.getParser(backgroundQuery, otherReq);
          Query backgroundQ = parser.getQuery();
          BitSetCollector background = new BitSetCollector(searcher.getIndexReader().maxDoc());
          searcher.search(backgroundQ, background);
          backgroundSet = background.getBits();
        } catch (SyntaxError syntaxError) {
          throw new RuntimeException(syntaxError);
        } catch (IOException e) {
          throw new RuntimeException(e);
        } finally {
          otherReq.close();
        }
      }

      return new SignifcantTermsCollector(rb, searcher, field, numTerms, minDocs, maxDocs, minTermLength, backgroundSet);
    }
  }

  private static class SignifcantTermsCollector extends DelegatingCollector {

    private String field;
    private IndexSearcher searcher;
    private ResponseBuilder rb;
    private int numTerms;
    private SparseFixedBitSet docs;
    private int numDocs;
    private float minDocs;
    private float maxDocs;
    private int count;
    private int minTermLength;
    private int highestCollected;
    private BitSet backgroundSet;

    public SignifcantTermsCollector(ResponseBuilder rb, IndexSearcher searcher, String field, int numTerms, float minDocs, float maxDocs, int minTermLength, BitSet backgroundSet) {
      this.rb = rb;
      this.searcher = searcher;
      this.field = field;
      this.numTerms = numTerms;
      this.docs = new SparseFixedBitSet(searcher.getIndexReader().maxDoc());
      this.numDocs = searcher.getIndexReader().numDocs();
      this.minDocs = minDocs;
      this.maxDocs = maxDocs;
      this.minTermLength = minTermLength;
      this.backgroundSet = backgroundSet;
    }

    @Override
    protected void doSetNextReader(LeafReaderContext context) throws IOException {
      super.doSetNextReader(context);
    }

    @Override
    public void collect(int doc) throws IOException {
      super.collect(doc);
      highestCollected = context.docBase + doc;
      docs.set(highestCollected);
      ++count;
    }

    @Override
    public void finish() throws IOException {
      List<String> outTerms = new ArrayList();
      List<Integer> outFreq = new ArrayList();
      List<Integer> outQueryFreq = new ArrayList();
      List<Double> scores = new ArrayList();

      NamedList<Integer> allFreq = new NamedList();
      NamedList<Integer> allQueryFreq = new NamedList();

      rb.rsp.add("numDocs", numDocs);
      rb.rsp.add("resultCount", count);
      rb.rsp.add("sterms", outTerms);
      rb.rsp.add("scores", scores);
      rb.rsp.add("docFreq", outFreq);
      rb.rsp.add("queryDocFreq", outQueryFreq);

      //TODO: Use a priority queue
      TreeSet<TermWithScore> topTerms = new TreeSet<>();

      Terms terms = MultiFields.getFields(searcher.getIndexReader()).terms(field);
      TermsEnum termsEnum = terms.iterator();
      BytesRef term;
      PostingsEnum postingsEnum = null;

      while ((term = termsEnum.next()) != null) {
        int docFreq = termsEnum.docFreq();

        if(minDocs < 1.0) {
          if((float)docFreq/numDocs < minDocs) {
            continue;
          }
        } else if(docFreq < minDocs) {
          continue;
        }

        if(maxDocs < 1.0) {
          if((float)docFreq/numDocs > maxDocs) {
            continue;
          }
        } else if(docFreq > maxDocs) {
          continue;
        }

        if(term.length < minTermLength) {
          continue;
        }

        int tf = 0;
        int bf = 0;
        postingsEnum = termsEnum.postings(postingsEnum);

        POSTINGS:
        while (postingsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
          int docId = postingsEnum.docID();

          if(docId > highestCollected && backgroundSet == null) {
            break POSTINGS;
          }

          if (backgroundSet != null) {
            if (backgroundSet.get(docId)) {
              ++bf;
              if (docs.get(docId)) {
                ++tf;
              }
            }
          } else {
            if (docs.get(docId)) {
              ++tf;
            }
          }
        }

        if(tf == 0) {
          continue;
        }

        if (backgroundSet != null ) {
          docFreq = bf;
        }

        float score = (float)Math.log(tf) * (float) (Math.log(((float)(numDocs + 1)) / (docFreq + 1)) + 1.0);

        String t = term.utf8ToString();
        allFreq.add(t, docFreq);
        allQueryFreq.add(t, tf);

        if (topTerms.size() < numTerms) {
          topTerms.add(new TermWithScore(term.utf8ToString(), score));
        } else  {
          if (topTerms.first().score < score) {
            topTerms.pollFirst();
            topTerms.add(new TermWithScore(term.utf8ToString(), score));
          }
        }
      }

      for (TermWithScore topTerm : topTerms) {
        outTerms.add(topTerm.term);
        scores.add(topTerm.score);
        outFreq.add(allFreq.get(topTerm.term));
        outQueryFreq.add(allQueryFreq.get(topTerm.term));
      }

      if (this.delegate instanceof DelegatingCollector) {
        ((DelegatingCollector) this.delegate).finish();
      }
    }
  }

  private static class BitSetCollector extends SimpleCollector {

    private FixedBitSet bits;
    private int docBase;

    public BitSetCollector(int maxDoc) {
      bits = new FixedBitSet(maxDoc);
    }

    @Override
    public void doSetNextReader(LeafReaderContext context) {
      this.docBase = context.docBase;
    }

    @Override
    public boolean needsScores() {
      return false;
    }

    @Override
    public void collect(int doc) throws IOException {
      bits.set(this.docBase+doc);
    }

    public BitSet getBits() {
      return bits;
    }
  }

  private static class TermWithScore implements Comparable<TermWithScore>{
    public final String term;
    public final double score;

    public TermWithScore(String term, double score) {
      this.term = term;
      this.score = score;
    }

    @Override
    public int hashCode() {
      return term.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (obj.getClass() != getClass()) return false;
      TermWithScore other = (TermWithScore) obj;
      return other.term.equals(this.term);
    }

    @Override
    public int compareTo(TermWithScore o) {
      int cmp = Double.compare(this.score, o.score);
      if (cmp == 0) {
        return this.term.compareTo(o.term);
      } else {
        return cmp;
      }
    }
  }
}