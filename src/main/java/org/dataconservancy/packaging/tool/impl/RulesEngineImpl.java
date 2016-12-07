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
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.impl.PropertyImpl;
import com.hp.hpl.jena.rdf.model.impl.StatementImpl;
import com.hp.hpl.jena.vocabulary.RDF;
import org.dataconservancy.packaging.tool.api.RulesEngine;
import org.dataconservancy.packaging.tool.api.RulesEngineException;
import org.dataconservancy.packaging.tool.impl.rules.FileContext;
import org.dataconservancy.packaging.tool.impl.rules.FileContextImpl;
import org.dataconservancy.packaging.tool.impl.rules.Mapping;
import org.dataconservancy.packaging.tool.impl.rules.Rule;
import org.dataconservancy.packaging.tool.impl.rules.RuleImpl;
import org.dataconservancy.packaging.tool.model.rules.Action;
import org.dataconservancy.packaging.tool.model.rules.RulesSpec;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.dataconservancy.dcs.util.FilePathUtil.prependFileUriPrefix;

/**
 * Created by jrm on 9/2/16.
 */

public class RulesEngineImpl implements RulesEngine {

    private final List<Rule> rules = new ArrayList<>();

    private List<Statement> statements = new ArrayList<>();

    private Set<String> visitedFiles = new HashSet<>();

    private Map<Object, URI> entityUris = new HashMap<>();

    private Model model = ModelFactory.createDefaultModel();

    RulesEngineImpl(RulesSpec rulesSpec) {
        rules.addAll(rulesSpec.getRule().stream().map(RuleImpl::new).collect(Collectors.toList()));
    }

    @Override
    public Model generateRdf(File directoryTreeRoot) throws RulesEngineException {
        if (directoryTreeRoot == null) {
            throw new RulesEngineException("The provided directory is null.");
        } else if (!directoryTreeRoot.exists()) {
            throw new RulesEngineException("The directory specified by file path \'"
                    + directoryTreeRoot.getPath() + "\' does not exist");
        } else if (!directoryTreeRoot.canRead()) {
            throw new RulesEngineException("The specified directory cannot be read.");
        }

       /*
        * make sure we have a clear file Set before we
        * start
        */
        visitedFiles.clear();

    /*
     * Create resources for each filesystem entity and add Statements to the Statement list
     */

        visitFile(new FileContextImpl(directoryTreeRoot, directoryTreeRoot, false));
        //finally, dump all the statements into the model
        model.add(statements);
        return model;
    }

    private void visitFile(FileContext cxt)
            throws RulesEngineException {

        try {
            String path = cxt.getFile().getCanonicalPath();
            if (visitedFiles.contains(path)) {
                if (Files.isSymbolicLink(cxt.getFile().toPath())) {
                    throw new RulesEngineException("Symbolic link cycle detected",
                            "Fix offending symbolic link at "
                                    + cxt.getFile()
                                    .toString()
                                    + ", which points to "
                                    + path);
                } else {
                    throw new RulesEngineException("Symbolic link cycle detected",
                            "There is a symbolic link under "
                                    + cxt.getRoot()
                                    .toString()
                                    + " which points to "
                                    + path
                                    + ".  Find the link and remove it.");
                }
            } else {
                visitedFiles.add(path);
            }
        } catch (IOException e) {
            throw new RulesEngineException("Error determining canonical path of "
                    + cxt.getFile(),
                    e);
        }

        try {
            for (Rule rule : rules) {
                if (rule.select(cxt)) {
                    if (Action.EXCLUDE.equals(rule.getAction())) {
                        cxt.setIgnored(true);
                        continue;
                    } else if (Action.INCLUDE.equals(rule.getAction())) {
                        populate(cxt, rule);
                    }

                    break;
                }
            }

        } catch (Exception e) {
            throw new RulesEngineException("Error applying rules to pathname "
                    + cxt.getFile()

                    .toString()
                    + ": \n"
                    + e.getMessage(),
                    e);
        }

        if (cxt.getFile().isDirectory()) {
            for (File child : cxt.getFile().listFiles()) {
                visitFile(new FileContextImpl(child, cxt.getRoot(), cxt.isIgnored()));
            }
        }
    }

    /*
     * Create jena Resource from the file, add Statements to the Statement List
     */
    private void populate(FileContext cxt,
                          Rule rule) {
        List<Mapping> mappings = rule.getMappings(cxt);

        for (Mapping mapping : mappings) {

            Path rootPath = Paths.get(cxt.getRoot().getParentFile().getPath());
            Path filePath = Paths.get(cxt.getFile().getPath());
            String relativeFilePathString = rootPath.relativize(filePath).toString();

            if (mappings.size() > 1) {
                relativeFilePathString = relativeFilePathString + "#" + mapping.getSpecifier();
            }


            URI rootUri = null;
            try {
                rootUri = new URI("file:" + rootPath.toString());
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }

            URI uri = findURI(relativeFilePathString);
            String subjectResourceUriString = uri.toString();

            Resource subjectResource = model.getResource(String.valueOf(subjectResourceUriString));
            if (subjectResource == null) {
                subjectResource = model.createResource(String.valueOf(subjectResourceUriString));
            }

            for (Map.Entry<String, List<String>> entry : mapping.getProperties().entrySet()) {
                Set<String> valueSet = new HashSet<>(entry.getValue());

                //Property property = new PropertyImpl(defaultScheme, entry.getKey());
                Property property = new PropertyImpl(entry.getKey());
                for (String value : valueSet) {
                        subjectResource.addProperty(property, value);
                }

            }

            for (Map.Entry<String, Set<URI>> rel : mapping
                    .getRelationships().entrySet()) {
                        //Property relProperty =   new PropertyImpl(defaultScheme, rel.getKey());
                        Property relProperty =   new PropertyImpl(rel.getKey());
                        for (URI target : rel.getValue()) {
                            Resource objectResource = model.getResource(String.valueOf(findURI(rootUri.relativize(target))));
                            if(objectResource == null) {
                                objectResource = model.createResource(String.valueOf(findURI(rootUri.relativize(target))));
                            }
                            statements.add(new StatementImpl(subjectResource, relProperty, objectResource));
                        }
            }

            model.add(subjectResource, RDF.type, mapping.getType().getValue());
        }
    }

    private URI findURI(Object key)  {
        String keyString = key.toString();

        if (keyString.endsWith(File.separator)) {
                keyString = keyString.substring(0, keyString.length() - 1);
        }

        if (entityUris.get(keyString) == null){
            try {
                entityUris.put(keyString, new URI("urn:" + UUID.randomUUID().toString()));
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
        return entityUris.get(keyString);
    }

}