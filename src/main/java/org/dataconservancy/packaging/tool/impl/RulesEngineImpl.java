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
import com.hp.hpl.jena.vocabulary.DC;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


/**
 * This class traverses a file tree containing content, and processes rules for each file system entity encountered.
 * The end result is a jena model which contains resources and statements as specified by the rules. Since rules may
 * create relationships to other resources which may not have been previously encountered in the traversal, we create
 * jena resources for any referenced entities on the fly, even if before they are encountered in the traversal.
 * This allows us to process the rules in a single pass through the content.
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


        if (!cxt.isIgnored()) {
            try {
                for (Rule rule : rules) {
                    if (rule.select(cxt)) {
                        if (Action.EXCLUDE.equals(rule.getAction())) {
                            cxt.setIgnored(true);
                            break;
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
        }

        if (cxt.getFile().isDirectory()) {
            for (File child : cxt.getFile().listFiles()) {
                visitFile(new FileContextImpl(child, cxt.getRoot(), cxt.isIgnored()));
            }
        }
    }

    /*
     * Create jena Resource from the file, add Statements to the Statement List
     * The resource may already be present in the model if it needed to be created earlier, as a target
     * of a relationship for example.
     */
    private void populate(FileContext cxt,
                          Rule rule) throws RulesEngineException {
        List<Mapping> mappings = rule.getMappings(cxt);

        for (Mapping mapping : mappings) {

            URI rootUri = cxt.getRoot().getParentFile().toURI();
            URI fileUri = cxt.getFile().toURI();
            URI relativeFileUri = rootUri.relativize(fileUri);

            String relativeFilePathString = relativeFileUri.toString();

            if (mappings.size() > 1) {
                relativeFilePathString = relativeFilePathString + "#" + mapping.getSpecifier();
            }

            //grab the package URI for this resource if it exists already, create it if not
            URI uri = findOrAssignURI(relativeFilePathString);
            String subjectResourceUriString = uri.toString();

            //... and grab the resource if it exists already, create it if not
            Resource subjectResource = model.getResource(subjectResourceUriString);
            if (subjectResource == null) {
                subjectResource = model.createResource(subjectResourceUriString);
            }

            //record the relative file path string as a DC "source" property
            subjectResource.addProperty(DC.source, relativeFilePathString);

            for (Map.Entry<String, List<String>> entry : mapping.getProperties().entrySet()) {
                Set<String> valueSet = new HashSet<>(entry.getValue());

                Property property = new PropertyImpl(entry.getKey());
                for (String value : valueSet) {
                        subjectResource.addProperty(property, value);
                }

            }

            for (Map.Entry<String, Set<URI>> rel : mapping
                    .getRelationships().entrySet()) {
                        Property relProperty =   new PropertyImpl(rel.getKey());
                        String targetResourceUriString;
                        for (URI target : rel.getValue()) {
                            targetResourceUriString = String.valueOf(rootUri.relativize(target));
                            //grab the package URI for this resource if it exists already, create it if not
                            URI targetResourceUri = findOrAssignURI(targetResourceUriString);
                            //... and grab the resource if it exists already, create it if not
                            Resource objectResource = model.getResource(String.valueOf(targetResourceUri));
                            if(objectResource == null) {
                                objectResource = model.createResource(String.valueOf(targetResourceUri));
                            }
                            statements.add(new StatementImpl(subjectResource, relProperty, objectResource));
                        }
            }

            model.add(subjectResource, RDF.type, mapping.getType().getValue());
        }
    }

    /**
     * When we encounter an entity for which we need a Resource in the Model, we look to see if it has been created yet.
     * This requires a URI. We check to see if the URI is present in the Map of Resources which have already been
     * created. If it is there, we just return it. If not, we create a new one and return that.
     *
     * @param key - A string representing the Resource for which we need a URI - generally a relative path
     * @return - a URI to identify this resource uniquely within the package
     */
    private URI findOrAssignURI(String key) throws RulesEngineException {

        if (entityUris.get(key) == null){
            try {
                entityUris.put(key, new URI("urn::" + UUID.randomUUID().toString()));
            } catch (URISyntaxException e) {
                throw new RulesEngineException("Error creating URI for " + key, e);
            }
        }

        return entityUris.get(key);
    }

}