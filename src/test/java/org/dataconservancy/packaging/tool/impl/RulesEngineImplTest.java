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
import net.lingala.zip4j.core.ZipFile;
import org.apache.commons.io.IOUtils;
import org.dataconservancy.packaging.tool.api.RulesEngine;
import org.dataconservancy.packaging.tool.model.PackageDescriptionRulesBuilder;
import org.dataconservancy.packaging.tool.model.builder.xstream.JaxbPackageDescriptionRulesBuilder;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Created by jrm on 10/27/16.
 */
public class RulesEngineImplTest {

    private static RulesEngine engine;

    private static Model model;

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

    //private static PackageDescription desc;

    private static File rootArtifactDir;

    //private static String packageOntologyIdentifier = "ontologyIdentifier";

    @ClassRule
    public static TemporaryFolder tmpfolder = new TemporaryFolder();

    @BeforeClass
    public static void setUp() throws Exception {
        InputStream zipInputStream =
                org.dataconservancy.packaging.tool.impl.RulesEngineImplTest.class
                        .getClassLoader()
                        .getResourceAsStream("TestContent/uc2a.zip");
        File temp =
                tmpfolder.newFolder("uc2a");

        File zipFile =
                tmpfolder.newFile("uc2a.zip");

        OutputStream zipOutputStream = new FileOutputStream(zipFile);

        IOUtils.copy(zipInputStream, zipOutputStream);
        zipOutputStream.close();
        zipInputStream.close();

        ZipFile zip = new ZipFile(zipFile);
        zip.extractAll(temp.getPath());

        rootArtifactDir = new File(temp, "test");
        if (!rootArtifactDir.isDirectory()) {
            throw new RuntimeException();
        }

    /*
     * OK, now that we have the content directory, load the rules and create
     * a rules engine
     */
        InputStream rulesStream =
                org.dataconservancy.packaging.tool.impl.RulesEngineImplTest.class.getClassLoader()
                        .getResourceAsStream("rules/default-rules.xml");

        PackageDescriptionRulesBuilder builder =
                new JaxbPackageDescriptionRulesBuilder();

        engine = new RulesEngineImpl(builder.buildPackageDescriptionRules(rulesStream));

        model =  engine.generateRdf(rootArtifactDir);


    }

    @Test
    public void  spitOutModel(){
             model.write(System.out,"TURTLE");
    }


    }
