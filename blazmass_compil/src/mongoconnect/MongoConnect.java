/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package mongoconnect;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.*;
import java.util.List;
import java.util.ArrayList;
import blazmass.dbindex.MassRange;
import blazmass.dbindex.IndexedSequence;
import blazmass.io.SearchParams;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import java.util.Iterator;
import org.bson.Document;
import org.bson.conversions.Bson;


/**
 *
 * @author greg
 */
public class MongoConnect {

    private MongoClient mongoClient;
    private MongoDatabase massDB;
    private MongoDatabase seqDB;
    private MongoDatabase protDB;
    private MongoCollection<Document> massDBCollection;
    private MongoCollection<Document> seqDBCollection;
    private MongoCollection<Document> protDBCollection;
    private static final MongoClientOptions.Builder options = MongoClientOptions.builder()
                                    .connectTimeout(5000) //default is 0 (no timeout)
                                    .socketTimeout(300000) //default is 0 (no timeout). 300 sec = 5 min
                                    .serverSelectionTimeout(30000); //default 
    
    public MongoConnect(SearchParams sParam) {
        connectToMongoDB(sParam);
        connectToMassDB(sParam);
        if (sParam.isUsingProtDB())
            connectToProtDB(sParam);
        if (sParam.isUsingSeqDB())
            connectToSeqDB(sParam);
    }
    
    public void disconnect(){
        System.out.println(mongoClient);
        if (mongoClient != null){
            mongoClient.close();
            System.out.println("Closed mongo connection");
        }else
            System.out.println("mo mongo connection to close");
    }
    
    /* connenctToMongoDB
    
    Connects to MongoDB using the mongoURI in SearchParams.
    massdb, seqdb, and protdb will use this one connection.
    Doing this to attempt to cut down on the number of connections that are being made.
    */
    private void connectToMongoDB(SearchParams sParam) {
        mongoClient = new MongoClient(new MongoClientURI(sParam.getMongoDBURI(), options));
        System.out.println("------Making new connection to MongoDB at " + mongoClient);
        
        /*  // Connect to 4 random mongos hosts out of the full list
        MongoClientURI x = new MongoClientURI(sParam.getMassDBURI(), options);
        List<String> unmodifiableHosts = x.getHosts();
        List<String> hosts = new ArrayList<String>(unmodifiableHosts);
        Collections.shuffle(hosts);
        List<String> newHosts = hosts.subList(0, 4);
        List<ServerAddress> newServers = new ArrayList<>();
        for (String host:newHosts){
            newServers.add(new ServerAddress(host));
        }

        MongoClient newclient = new MongoClient(newServers, options.build());
        System.out.println(newclient.getAllAddress());
        */
    }
    
    /*
     connectToMassDB
    
     Connects to MongoDB 'MassDB' dbname, collection name specified in blazmass.params file (sParam object).
        
     Sets variables massDB, massDBCollection for use 
     throughout the class.  Should only make one of each object per thread.
     */
    private void connectToMassDB(SearchParams sParam) {
        try {
            massDB = mongoClient.getDatabase(sParam.getMassDBName());
            massDBCollection = massDB.getCollection(sParam.getMassDBCollection());
            System.out.println("Connecting to massdb: " + massDBCollection.getNamespace());
            if (massDBCollection.count() == 0 ){
                System.out.println("Massdb is empty!");
                System.exit(1); 
            }
        } catch (MongoException e) {
            System.out.println("connectToMassDB error");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /*
     connectToSeqDB
    
     Connects to MongoDB 'SeqDB' with dbname, 
     collection name specified in blazmass.params file (sParam object).
     (using SeqDB is optional)
        
     Sets variables seqDB, seqDBCollection for use 
     throughout the class.  Should only make one of each connection object, 
     to be reused many times.
     */
    private void connectToSeqDB(SearchParams sParam) {
        try {
            seqDB = mongoClient.getDatabase(sParam.getSeqDBName());
            seqDBCollection = seqDB.getCollection(sParam.getSeqDBCollection());
            System.out.println(seqDBCollection);
        } catch (MongoException e) {
            System.out.println("connectToSeqDB error");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /*
     connectToProtDB
    
     Connects to MongoDB 'ProtDB' with dbname, 
     collection name specified in blazmass.params file (sParam object).
     (using ProtDB is optional)
        
     Sets variables protDB, protDBCollection for use 
     throughout the class.  Should only make one of each connection object, 
     to be reused many times.
     */
    private void connectToProtDB(SearchParams sParam) {
        try {
            protDB = mongoClient.getDatabase(sParam.getProtDBName());
            protDBCollection = protDB.getCollection(sParam.getProtDBCollection());
            System.out.println(protDBCollection);
        } catch (MongoException e) {
            System.out.println("connectToProtDB error");
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /*
    getSequencesIter: Does a range query. Reture an iterator that yields IndexedSequences containing a single peptide and its mass
     */
    public MongoSeqIter getSequencesIter(List<MassRange> rList, SearchParams sParam) {        
        int lowMass;
        int highMass;
        // Build long "or" query using all mass ranges
        List<Bson> orList = new ArrayList();
        for (MassRange mRange : rList) {
            lowMass = Math.round((mRange.getPrecMass() - mRange.getTolerance()) * 1000);
            highMass = Math.round((mRange.getPrecMass() + mRange.getTolerance()) * 1000);
            Bson x = and(gte("_id", lowMass), lte("_id", highMass));
            orList.add(x);
        }
        Bson query = or(orList);
        FindIterable<Document> cursor = massDBCollection.find(query).batchSize(3000);
        MongoSeqIter msi = new MongoSeqIter(cursor);
        return msi;
    }

    /*
     makeIndexedSequence
    
     Takes an integer mass (exact mass rounded to 3 decimal points * 1000) 
     and a peptide sequence string as inputs.
    
     Returns an IndexedSequence object as output.
     */
    public static IndexedSequence makeIndexedSequence(int intMass, String sequence) throws Exception {
        try {
            float floatMass = (float) intMass / 1000; //'_id' is 'MASS'
            IndexedSequence indSeq = new IndexedSequence(floatMass, sequence, sequence.length(), "---", "---");
            return indSeq;
        } catch (Exception e) {
            System.out.println("DB Query / getSequencesFromColl error");
            return null;
        }
    }

  
    /*
     getParents
    
     Takes a peptide sequence string and SearchParams object as inputs.  Uses
     MongoDB SeqDB and (optionally) ProtDB.
    
     Returns a list of parent proteins, each formatted in SQT L line format.
     */
    public List<String> getParents(String peptideSequence, SearchParams sParam, boolean isReversePeptide) throws Exception {
        List<String> parentProteinsOfPeptide = new ArrayList<>();
        if (sParam.isUsingSeqDB()) {
            return getParentsFromColl(peptideSequence, sParam, isReversePeptide);
        } else {
            parentProteinsOfPeptide.add("");
            return parentProteinsOfPeptide; // return list with a single empty string (if not using SeqDB)
        }
    }

    /*
     getParentsFromColl
    
     Helper method for getParents.  Takes a peptide sequence string and 
     SearchParams object as inputs.
    
     Constructs and executes MongoDB findOne query using peptide sequence.
    
     Returns a list of parent proteins, each formatted in SQT L line format.
     */
    private List<String> getParentsFromColl(String peptideSequence, SearchParams sParam, boolean isReversePeptide) throws Exception {
        
        List<String> parentProteinsOfPeptide = new ArrayList<>();

        try {
            Document peptideDocument = seqDBCollection.find(eq("_id", peptideSequence)).first();

            if (peptideDocument == null) {
                parentProteinsOfPeptide.add(""); // return list with a single empty string (if no parent proteins found)
            } else {
                ArrayList<Document> parentProteins = (ArrayList<Document>) peptideDocument.get("p");

                if (parentProteins.isEmpty()) { // this shouldn't happen.  Shouldn't be peptides in SeqDB without parent proteins...
                    parentProteinsOfPeptide.add(""); // return list with a single empty string (if no parent proteins found)
                } else {
                    for (Document parent : parentProteins) {
                        parentProteinsOfPeptide.add(parseParentObjectSimple(peptideSequence, parent, sParam, isReversePeptide));
                    }
                }
            }
            return parentProteinsOfPeptide;
        } catch (Exception e) {
            System.out.println("SeqDB Query retrieval error");
            e.printStackTrace();
            return null;
        }
    }

    /*
     parseParentObjectSimple
        
     Helper method for getParentsFromColl.  Takes a peptide sequence and a
     single parent DBObject as input (along with SearchParams object).
    
     Processes parent object and formats output in SQT L line format.
     Calls parseParentObjectDetailed if using ProtDB.
    
    if isReversePeptide, prepend "Reverse_" on the protID no matter what
     */
    private String parseParentObjectSimple(String peptideSequence, Document parent, SearchParams sParam, boolean isReversePeptide) throws Exception {
        // example parent object:
        // { "_id" : "DYMAAGLYDRAEDMFSQLINEEDFR", "p" : [ { "i" : 20915500, "r" : "VSA", "l" : "LGR", "o" : 115 }, { "i" : 21556668, "r" : "VSA", "l" : "LGR", "o" : 115 } ] }
        try {
            String myParent;
            int parentID = ((Number) parent.get("i")).intValue();
            String parentIDString;

            if (sParam.isUsingProtDB())
                parentIDString = String.valueOf(parentID) + "||" + parseParentObjectDetailed(parentID, sParam);
            else
                parentIDString = String.valueOf(parentID);
            
            if ((parent.containsKey("d") && (boolean) parent.get("d") == true) || isReversePeptide)
                myParent = "Reverse_";
            else
                myParent = "";
            
            myParent += parentIDString + "\t0\t"
                    + (String) parent.get("l") + "."
                    + peptideSequence + "." + (String) parent.get("r");
            
            // For future information. DTASelect seems perfectly happy to accept the peptide string
            // without the left and right 3 residues, like below:
            // myParent += parentIDString + "\t0\t" + peptideSequence;
            
            return myParent;

        } catch (Exception e) {
            System.out.println("SeqDB parseParentObjectSimple error");
            e.printStackTrace();
            return null;
        }
    }

    /*
     parseParentObjectDetailed
    
     Helper method for parseParentObjectSimple.  Takes an integer parent 
     protein ID and SearchParams object as inputs.
    
     Queries MongoDB ProtDB for FASTA defline (protein name, species etc.)
    
     Returns FASTA defline if found in ProtDB; otherwise returns string
     parent protein ID.
     */
    private String parseParentObjectDetailed(int parentID, SearchParams sParam) throws Exception {
        try {
            String proteinDefline;
            Document proteinDocument = protDBCollection.find(eq("_id", parentID)).first();

            if (proteinDocument == null) {
                // if there is no result from the ProtDB query...
                return null;
            } else {
                // if there is a result from the query...
                proteinDefline = (String) proteinDocument.get("d");
                if (proteinDefline == null) {
                    // if there is no defline result from the query...
                    return String.valueOf(parentID);
                } else {
                    // if there is a defline key in the query result...
                    return proteinDefline;
                }
            }
        } catch (Exception e) {
            System.out.println("SeqDB parseParentObjectDetailed error");
            return null;
        }
    }

    public static void main(String[] args) throws Exception {

        try {

        } catch (Exception e) {
            System.out.println("general error");
        }
    }

}
