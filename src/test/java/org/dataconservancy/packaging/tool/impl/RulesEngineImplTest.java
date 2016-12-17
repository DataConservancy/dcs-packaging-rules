/*
 * Copyright 2016 Johns Hopkins University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dataconservancy.packaging.tool.impl;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.NodeIterator;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.impl.PropertyImpl;
import net.lingala.zip4j.core.ZipFile;
import org.apache.commons.io.IOUtils;
import org.dataconservancy.packaging.tool.api.RulesEngine;
import org.dataconservancy.packaging.tool.model.PackageDescriptionRulesBuilder;
import org.dataconservancy.packaging.tool.model.builder.xstream.JaxbPackageDescriptionRulesBuilder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jrm on 10/27/16.
 */
public class RulesEngineImplTest {

    private static RulesEngine engine;

    private static Model model;

    private static Map<String, String> testUris = new HashMap<>();

    private static String propertyUriBase = "http://dataconservancy.org/business-object-model#";
    private static Property titleProperty = new PropertyImpl(propertyUriBase + "hasTitle");
    private static Property memberProperty = new PropertyImpl(propertyUriBase + "isMemberOf");
    private static Property createdProperty = new PropertyImpl(propertyUriBase + "hasCreateDate");
    private static Property modifiedProperty = new PropertyImpl(propertyUriBase + "hasModifiedDate");
    private static Property formatProperty = new PropertyImpl(propertyUriBase + "hasFormat");
    private static Property sizeProperty = new PropertyImpl(propertyUriBase + "hasSize");
    private static Property metadataProperty = new PropertyImpl(propertyUriBase + "isMetadataFor");
    private static Property typeProperty = new PropertyImpl("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");

    /* Directories that should be projects */
    private static final List<String> ROOT_COLLECTION_PATHS = Arrays.asList("");
    /* Directories that should be empty collections */
    private static final List<String> EMPTY_COLLECTION_PATHS = Arrays
            .asList("empty_collection");

    /* Directories that should be collections */
    private static final List<String> COLLECTION_PATHS = Arrays
            .asList("collection1",
                    "collection2",
                    "empty_collection",
                    "collection2/subcollection2.0",
                    "collection2/subcollection2.1");

    /* Directories that are collections, but also subcollections */
    private static final List<String> SUBCOLLECTION_PATHS = Arrays
            .asList("collection2/subcollection2.0",
                    "collection2/subcollection2.1");

    /* Directories that should be DataItems */
    private static final List<String> DATA_ITEM_PATHS = Arrays
            .asList("collection1/dataItem1.0",
                    "collection1/dataItem1.1",
                    "collection2/subcollection2.0/dataItem2.0.0",
                    "collection2/subcollection2.1/dataItem2.1.0");

    /* Files that should be DataFiles */
    private static final List<String> DATA_FILE_PATHS = Arrays
            .asList("collection1/dataItem1.0/dataFile1.0.0",
                    "collection1/dataItem1.0/dataFile1.0.1",
                    "collection1/dataItem1.0/dataFile1.0.2",
                    "collection1/dataItem1.1/dataFile1.1.0",
                    "collection1/dataItem1.1/dataFile1.1.1",
                    "collection2/subcollection2.0/dataItem2.0.0/dataFile2.0.0",
                    "collection2/subcollection2.0/dataItem2.0.0/dataFile2.0.1",
                    "collection2/subcollection2.1/dataItem2.1.0/dataFile2.1.0");

    /* Files that should be MetadataFiles */
    private static final List<String> METADATA_FILE_PATHS = Arrays
            .asList("metadata_for_project.txt",
                    "collection1/metadataFile1.0",
                    "collection2/metadataFile2.0");

    private static final List<String> DOT_PATHS = Arrays
            .asList(".dotfile", ".dotdirectory", ".dotdirectory/excluded.txt");

    private static File rootArtifactDir;

    @ClassRule
    public static TemporaryFolder tmpfolder = new TemporaryFolder();

    @BeforeClass
    public static void setUp() throws Exception {
        InputStream zipInputStream =
                org.dataconservancy.packaging.tool.impl.RulesEngineImplTest.class
                        .getClassLoader()
                        .getResourceAsStream("RulesEngineTest.zip");
        File temp =
                tmpfolder.newFolder("RulesEngineTest");

        File zipFile =
                tmpfolder.newFile("RulesEngineTest.zip");

        OutputStream zipOutputStream = new FileOutputStream(zipFile);

        IOUtils.copy(zipInputStream, zipOutputStream);
        zipOutputStream.close();
        zipInputStream.close();

        ZipFile zip = new ZipFile(zipFile);
        zip.extractAll(temp.getPath());

        rootArtifactDir = new File(temp, "content");
        if (!rootArtifactDir.isDirectory()) {
            throw new RuntimeException();
        }

        /*
         * OK, now that we have the content directory, load the rules and create
         * a rules engine
         */
        InputStream rulesStream =
                org.dataconservancy.packaging.tool.impl.RulesEngineImplTest.class.getClassLoader()
                        .getResourceAsStream("rules/default-engine-rules.xml");

        PackageDescriptionRulesBuilder builder =
                new JaxbPackageDescriptionRulesBuilder();

        engine = new RulesEngineImpl(builder.buildPackageDescriptionRules(rulesStream));

        model =  engine.generateRdf(rootArtifactDir);

        // populate the map from filenames to URIs for the test model
        // we need to map the URIs because they are generated on the fly by the RulesEngine
        StmtIterator titleItr = model.listStatements(null, titleProperty, (RDFNode) null);
        while(titleItr.hasNext()){
            Statement s = titleItr.nextStatement();
            testUris.put(s.getString(), s.getSubject().toString());
        }

    }

    @Test
    public void testExistence(){
         for (String pathString : COLLECTION_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : DATA_ITEM_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : DATA_FILE_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : METADATA_FILE_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }
    }

    @Test
    public void testNonExistence(){
        for (String pathString : DOT_PATHS) {
           Path path = Paths.get(pathString);
           String file = path.getFileName().toString();
           Assert.assertNull(testUris.get(file));
        }
    }

    @Test
    public void testMembership(){
         for (String pathString : SUBCOLLECTION_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             String parent = childPath.getParent().getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             Resource object = model.getResource(testUris.get(parent));

             StmtIterator itr = model.listStatements(subject, memberProperty, object);
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
         }

         for (String pathString : DATA_ITEM_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             String parent = childPath.getParent().getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             Resource object = model.getResource(testUris.get(parent));

             StmtIterator itr = model.listStatements(subject, memberProperty, object);
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
         }

         for (String pathString : DATA_FILE_PATHS) {
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             String parent = childPath.getParent().getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             Resource object = model.getResource(testUris.get(parent));

             StmtIterator itr = model.listStatements(subject, memberProperty, object);
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());

         }
    }

    @Test
    public void testFileSizes(){
        for (String pathString : DATA_FILE_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, sizeProperty, String.valueOf(dataFile.length()));
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }

         for (String pathString : METADATA_FILE_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, sizeProperty, String.valueOf(dataFile.length()));
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }
    }

    @Test
    public void testType(){
        for (String pathString : COLLECTION_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, typeProperty, "Collection");
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }

         for (String pathString : DATA_ITEM_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, typeProperty, "DataItem");
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_FILE_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, typeProperty, "DataFile");
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : METADATA_FILE_PATHS) {
             Path path = Paths.get(pathString);
             String file = path.getFileName().toString();
             Resource subject = model.getResource(testUris.get(file));
             File dataFile = new File(rootArtifactDir, pathString);

             StmtIterator itr = model.listStatements(subject, typeProperty, "MetadataFile");
             List<Statement> statementList = itr.toList();
             Assert.assertEquals(1, statementList.size());
        }
    }


    //a few exploratory test methods, delete when this class is finished
    @Ignore
    @Test
    public void  spitOutModel() {
        model.write(System.out, "TURTLE");
    }

    @Ignore
    @Test
    public void spitOutProperties(){
        StmtIterator sitr = model.listStatements();
        while(sitr.hasNext()){
            Statement s = sitr.nextStatement();
            System.out.println(s.getPredicate().toString());
        }
    }

    @Ignore
    @Test
    public void  listObjects() {

        Property typeProperty = new PropertyImpl("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        StmtIterator typeItr = model.listStatements(null, typeProperty, (RDFNode) null);
        while(typeItr.hasNext()){
            Statement s = typeItr.nextStatement();
            System.out.println(s.toString());
            System.out.println(s.getSubject().toString());
            System.out.println(s.getString());
            System.out.println();
        }

        StmtIterator memberItr = model.listStatements(null, memberProperty, (RDFNode) null);
        while(memberItr.hasNext()){
            Statement s = memberItr.nextStatement();
            System.out.println(s.toString());
            System.out.println(s.getSubject().toString());
            System.out.println(s.getObject().toString());
            System.out.println();
        }


        ResIterator ritr = model.listSubjects();
        while(ritr.hasNext()){
            Resource r = ritr.nextResource();
            System.out.println(r.toString());
            System.out.println();
        }

        ResIterator titr = model.listResourcesWithProperty(titleProperty);
        while(titr.hasNext()){
            Resource r = titr.nextResource();
            System.out.println(r.toString());
            System.out.println();
        }

        ResIterator tyItr = model.listResourcesWithProperty(typeProperty);
        while(typeItr.hasNext()){
            Resource r = tyItr.nextResource();
            //System.out.println(r.toString());
            NodeIterator nodeItr = model.listObjectsOfProperty(typeProperty);
            while (nodeItr.hasNext()){
                RDFNode n = nodeItr.nextNode();
                System.out.println(n.toString());
            }
            System.out.println();
        }
    }

}
