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
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.impl.PropertyImpl;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.IOUtils;
import org.dataconservancy.dcs.model.DetectedFormat;
import org.dataconservancy.dcs.util.ContentDetectionService;
import org.dataconservancy.dcs.util.DateUtility;
import org.dataconservancy.packaging.tool.api.RulesEngine;
import org.dataconservancy.packaging.tool.api.RulesEngineException;
import org.dataconservancy.packaging.tool.model.PackageDescriptionRulesBuilder;
import org.dataconservancy.packaging.tool.model.builder.xstream.JaxbPackageDescriptionRulesBuilder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Class for RulesEngineImpl. We generate a jena model from a zip file in test resources, and then
 * test the model to make sure it contains the expected triples
 */
public class RulesEngineImplTest {

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
    private static Property sourceProperty = new PropertyImpl("http://purl.org/dc/elements/1.1/source");

    private static String topDir = "content";

    /* Directories that should be collections */
    private static final List<String> COLLECTION_PATHS = Arrays
            .asList("collection1",
                    "collection2",
                    "empty_collection",
                    "hybrid_collection",
                    "hybrid_collection/subcollection",
                    "collection2/subcollection2.0",
                    "collection2/subcollection2.1");

    /* Directories that are collections, but also subcollections */
    private static final List<String> SUBCOLLECTION_PATHS = Arrays
            .asList("collection2/subcollection2.0",
                    "collection2/subcollection2.1",
                    "hybrid_collection/subcollection");

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

    private static final List<String> HYBRID_PATHS = Arrays
            .asList("hybrid_collection/impliedDataFile1",
                    "hybrid_collection/impliedDataFile2",
                    "hybrid_collection/impliedDataFile3");

    private static File rootArtifactDir;

    @ClassRule
    public static TemporaryFolder tmpfolder = new TemporaryFolder();

    /**
     *  Set up the model generated from the provided zip file, and create a map for the package URIs
     * @throws IOException if the zip file temp zip file can't be created
     * @throws ZipException if the zip file can't be created
     * @throws RulesEngineException if the model can't be created
     */
    @BeforeClass
    public static void setUp() throws IOException, ZipException, RulesEngineException {
        InputStream zipInputStream =
                org.dataconservancy.packaging.tool.impl.RulesEngineImplTest.class
                        .getClassLoader()
                        .getResourceAsStream("RulesEngineTest.zip");
        File temp = tmpfolder.newFolder("RulesEngineTest");

        File zipFile =
                tmpfolder.newFile("RulesEngineTest.zip");

        OutputStream zipOutputStream = new FileOutputStream(zipFile);

        IOUtils.copy(zipInputStream, zipOutputStream);
        zipOutputStream.close();
        zipInputStream.close();

        ZipFile zip = new ZipFile(zipFile);
        zip.extractAll(temp.getPath());

        rootArtifactDir = new File(temp, topDir);
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

        RulesEngine engine = new RulesEngineImpl(builder.buildPackageDescriptionRules(rulesStream));

        model =  engine.generateRdf(rootArtifactDir);

        // populate the map from filenames to URIs for the test model
        // we need to map the URIs because they are generated on the fly by the RulesEngine
        StmtIterator titleItr = model.listStatements(null, titleProperty, (RDFNode) null);
        while (titleItr.hasNext()) {
            Statement s = titleItr.nextStatement();
            testUris.put(s.getString(), s.getSubject().toString());
        }

    }

    /**
     * Test to show that all the paths in the content directory give rise to resources in the model
     */
    @Test
    public void testExistence() {
         for (String pathString : COLLECTION_PATHS) {
             pathString = pathString.replace('/', File.separatorChar);
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : DATA_ITEM_PATHS) {
             pathString = pathString.replace('/', File.separatorChar);
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : DATA_FILE_PATHS) {
             pathString = pathString.replace('/', File.separatorChar);
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }

         for (String pathString : METADATA_FILE_PATHS) {
             pathString = pathString.replace('/', File.separatorChar);
             Path childPath = Paths.get(pathString);
             String file = childPath.getFileName().toString();
             Assert.assertNotNull(model.getResource(testUris.get(file)));
         }
    }

    /**
     * Test to shoiw that paths in the content directory which should be ignored, are ignored
     */
    @Test
    public void testNonExistence() {
        for (String pathString : DOT_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Assert.assertNull(testUris.get(file));
        }
    }

    /**
     * Test membership relationships
     */
    @Test
    public void testMembership() {
        for (String pathString : SUBCOLLECTION_PATHS) {
             pathString = pathString.replace('/', File.separatorChar);
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
             pathString = pathString.replace('/', File.separatorChar);
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
             pathString = pathString.replace('/', File.separatorChar);
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


    /**
     * Test metadata file relationships
     */
    @Test
    public void testMetadataness() {
        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path childPath = Paths.get(pathString);
            String file = childPath.getFileName().toString();
            String parent;

            if(childPath.getParent() == null){
                parent = topDir;
            } else {
                parent = childPath.getParent().getFileName().toString();
            }

            Resource subject = model.getResource(testUris.get(file));
            Resource object = model.getResource(testUris.get(parent));

            StmtIterator itr = model.listStatements(subject, metadataProperty, object);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }
    }

    /**
     * Test that appropriate files are converted to DataItem / DataFile pairs
     */
    @Test
    public void testHybrids() {
        for (String pathString : HYBRID_PATHS) {
           pathString = pathString.replace('/', File.separatorChar);
           Path path = Paths.get(pathString);
           String file = path.getFileName().toString();
           //both the DataItem and the DataFile will have title equal to the file name
           StmtIterator itr = model.listStatements(null, titleProperty, file );
           List<Statement> statementList = itr.toList();
           Assert.assertEquals(2, statementList.size());

           Resource resource0 = model.getResource(statementList.get(0).getSubject().getURI());
           Resource resource1 = model.getResource(statementList.get(1).getSubject().getURI());

           Assert.assertNotNull(resource0);
           Assert.assertNotNull(resource1);

           StmtIterator itr0 = model.listStatements(resource0, memberProperty, resource1);
           StmtIterator itr1 = model.listStatements(resource1, memberProperty, resource0);
           List<Statement> statementList0 = itr0.toList();
           List<Statement> statementList1 = itr1.toList();

           //one of these has to be the container of the other
           Assert.assertTrue((statementList0.size() == 0 && statementList1.size() == 1)
           || (statementList0.size() == 1 && statementList1.size() == 0));

           //Sort out which one is the DataItem, and which is the DataFile
           Resource dataItemResource;
           Resource dataFileResource;
           if (statementList0.size() == 0 && statementList1.size() == 1) {
               dataItemResource = resource0;
               dataFileResource = resource1;
           } else {
               dataItemResource = resource1;
               dataFileResource = resource0;
           }

           //verify the DataFile / DataItem relationship
           StmtIterator itr2 = model.listStatements(dataFileResource, memberProperty, dataItemResource);
           List<Statement> statementList2 = itr2.toList();
           Assert.assertEquals(1, statementList2.size());

           //The parent of the file object in the system will be the containing collection for the DataItem
           String parent = path.getParent().getFileName().toString();
           Resource object = model.getResource(testUris.get(parent));

           StmtIterator itr3 = model.listStatements(dataItemResource, memberProperty, object);

           //verify the DataItem / Collection relationship
           List<Statement> statementList3 = itr3.toList();
           Assert.assertEquals(1, statementList3.size());
        }
    }

    /**
     * Test the size property
     */
    @Test
    public void testFileSizes(){
        for (String pathString : DATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File dataFile = new File(rootArtifactDir, pathString);

            StmtIterator itr = model.listStatements(subject, sizeProperty, String.valueOf(dataFile.length()));
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File dataFile = new File(rootArtifactDir, pathString);

            StmtIterator itr = model.listStatements(subject, sizeProperty, String.valueOf(dataFile.length()));
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }
    }

    /**
     * Test the type property
     */
    @Test
    public void testType(){
        for (String pathString : COLLECTION_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));

            StmtIterator itr = model.listStatements(subject, typeProperty, "Collection");
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_ITEM_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));

            StmtIterator itr = model.listStatements(subject, typeProperty, "DataItem");
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));

            StmtIterator itr = model.listStatements(subject, typeProperty, "DataFile");
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));

            StmtIterator itr = model.listStatements(subject, typeProperty, "MetadataFile");
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }
    }

    /**
     * Test file modified proeprty
     */
    @Test
    public void testModifiedTimes() {
        for (String pathString : COLLECTION_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            String modifiedDate = DateUtility.toIso8601_DateTimeNoMillis(thisFile.lastModified());

            StmtIterator itr = model.listStatements(subject, modifiedProperty, modifiedDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_ITEM_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            String modifiedDate = DateUtility.toIso8601_DateTimeNoMillis(thisFile.lastModified());

            StmtIterator itr = model.listStatements(subject, modifiedProperty, modifiedDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            String modifiedDate = DateUtility.toIso8601_DateTimeNoMillis(thisFile.lastModified());

            StmtIterator itr = model.listStatements(subject, modifiedProperty, modifiedDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            String modifiedDate = DateUtility.toIso8601_DateTimeNoMillis(thisFile.lastModified());

            StmtIterator itr = model.listStatements(subject, modifiedProperty, modifiedDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
       }

    }

    /**
     * Test created time property
     */
    @Test
    public void testCreatedTimes() {
        for (String pathString : COLLECTION_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);
            BasicFileAttributes fileMetadata;
            try {
                fileMetadata =
                        Files.getFileAttributeView(thisFile.toPath(),
                                BasicFileAttributeView.class)
                                .readAttributes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            String createdDate = DateUtility.toIso8601_DateTimeNoMillis(new Date(fileMetadata.creationTime()
                        .toMillis()));
            StmtIterator itr = model.listStatements(subject, createdProperty, createdDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_ITEM_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);
            BasicFileAttributes fileMetadata;
            try {
                fileMetadata =
                        Files.getFileAttributeView(thisFile.toPath(),
                                BasicFileAttributeView.class)
                                .readAttributes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            String createdDate = DateUtility.toIso8601_DateTimeNoMillis(new Date(fileMetadata.creationTime()
                        .toMillis()));
            StmtIterator itr = model.listStatements(subject, createdProperty, createdDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : DATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);
            BasicFileAttributes fileMetadata;
            try {
                fileMetadata =
                        Files.getFileAttributeView(thisFile.toPath(),
                                BasicFileAttributeView.class)
                                .readAttributes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            String createdDate = DateUtility.toIso8601_DateTimeNoMillis(new Date(fileMetadata.creationTime()
                    .toMillis()));
            StmtIterator itr = model.listStatements(subject, createdProperty, createdDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }

        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);
            BasicFileAttributes fileMetadata;
            try {
                fileMetadata =
                        Files.getFileAttributeView(thisFile.toPath(),
                                BasicFileAttributeView.class)
                                .readAttributes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            String createdDate = DateUtility.toIso8601_DateTimeNoMillis(new Date(fileMetadata.creationTime()
                        .toMillis()));
            StmtIterator itr = model.listStatements(subject, createdProperty, createdDate);
            List<Statement> statementList = itr.toList();
            Assert.assertEquals(1, statementList.size());
        }
    }

    /**
     * Test format property
     */
    @Test
    public void testFormats() {

        for (String pathString : DATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            List<DetectedFormat> fileFormats = ContentDetectionService.getInstance().detectFormats(thisFile);
            List<Statement> detectedStatements = new ArrayList<>();

            String formatString = "";

            for (DetectedFormat format : fileFormats) {
                if (format.getId() != null && !format.getId().isEmpty()) {
                    formatString = "info:pronom/" + format.getId();
                } else if (format.getMimeType() != null && !format.getMimeType().isEmpty()) {
                    formatString = format.getMimeType();
                }
                StmtIterator itr = model.listStatements(subject, formatProperty, formatString);
                List<Statement> statementList = itr.toList();
                detectedStatements.addAll(statementList);
            }

            Assert.assertTrue(detectedStatements.size() > 0);
        }

        for (String pathString : METADATA_FILE_PATHS) {
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            File thisFile = new File(rootArtifactDir, pathString);

            List<DetectedFormat> fileFormats = ContentDetectionService.getInstance().detectFormats(thisFile);
            List<Statement> detectedStatements = new ArrayList<>();

            String formatString = "";

            for (DetectedFormat format : fileFormats){
                if (format.getId() != null && !format.getId().isEmpty()) {
                    formatString = "info:pronom/" + format.getId();
                } else if (format.getMimeType() != null && !format.getMimeType().isEmpty()) {
                    formatString = format.getMimeType();
                }
                StmtIterator itr = model.listStatements(subject, formatProperty, formatString);
                List<Statement> statementList = itr.toList();
                detectedStatements.addAll(statementList);
            }

            Assert.assertTrue(detectedStatements.size() > 0);

       }
    }

    /**
     * Test the DC source property
     */
    @Test
    public void testSource() {
        List<String> allPaths = new ArrayList<>();
        allPaths.addAll(COLLECTION_PATHS);
        allPaths.addAll(DATA_ITEM_PATHS);
        allPaths.addAll(DATA_FILE_PATHS);
        allPaths.addAll(METADATA_FILE_PATHS);

        for (String pathString : allPaths) {
            pathString = rootArtifactDir.getAbsolutePath() + File.separatorChar + pathString; 
            pathString = pathString.replace('/', File.separatorChar);
            Path path = Paths.get(pathString);
            String file = path.getFileName().toString();
            Resource subject = model.getResource(testUris.get(file));
            StmtIterator itr = model.listStatements(subject, sourceProperty, (RDFNode) null);
            List<Statement> statementList = itr.toList();

            Assert.assertEquals(1, statementList.size());

            Assert.assertEquals(pathString, statementList.get(0).getObject().toString());
        }

    }

}
