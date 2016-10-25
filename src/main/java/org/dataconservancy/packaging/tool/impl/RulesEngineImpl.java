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
import org.apache.http.client.utils.URIBuilder;
import org.dataconservancy.packaging.tool.api.RulesEngine;
import org.dataconservancy.packaging.tool.api.RulesEngineException;
import org.dataconservancy.packaging.tool.impl.rules.FileContext;
import org.dataconservancy.packaging.tool.impl.rules.FileContextImpl;
import org.dataconservancy.packaging.tool.impl.rules.Mapping;
import org.dataconservancy.packaging.tool.impl.rules.Rule;
import org.dataconservancy.packaging.tool.impl.rules.RuleImpl;
import org.dataconservancy.packaging.tool.model.PackageArtifact;
import org.dataconservancy.packaging.tool.model.PackageRelationship;
import org.dataconservancy.packaging.tool.model.rules.Action;
import org.dataconservancy.packaging.tool.model.rules.RuleSpec;
import org.dataconservancy.packaging.tool.model.rules.RulesSpec;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by jrm on 9/2/16.
 */

public class RulesEngineImpl implements RulesEngine {

    private final List<Rule> rules = new ArrayList<Rule>();

    private Set<String> visitedFiles = new HashSet<String>();

    public RulesEngineImpl(RulesSpec rulesSpec) {
            for (RuleSpec ruleSpec : rulesSpec.getRule()) {
                rules.add(new RuleImpl(ruleSpec));
            }
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
     * Create resources for each filesystem entity
     */
        visitFile(new FileContextImpl(directoryTreeRoot, directoryTreeRoot, false));


        Model model = ModelFactory.createDefaultModel();


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
            throw new RulesEngineException("Error applying package description generation rules to pathname "
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
     * Create PackageArtifact from the file, populate PackageDescription with it
     */
    private void populate(FileContext cxt,
                          Rule rule) {

        List<Mapping> mappings = rule.getMappings(cxt);

        for (Mapping mapping : mappings) {

            /* We are using file URI as artifact IDs, unless multiple mappings */
            URIBuilder urib = new URIBuilder(cxt.getFile().toURI());
            //String id = cxt.getFile().toURI().toString();

            /*
             * If multiple mappings implicated by this file, then make sure
             * they're differentiated
             */
            if (mappings.size() > 1) {
                String specifier = mapping.getSpecifier();
                if (specifier != null) {
                    urib.setFragment(specifier);
                }
            }

            URI uri = null;
            try {
                uri = urib.build();
            } catch (URISyntaxException e) {

            }
            String id = uri.toString();

            PackageArtifact artifact = new PackageArtifact();
           // artifacts.put(id, artifact);
            artifact.setId(id);
            artifact.setIgnored(cxt.isIgnored());
            //we need to relativize against the content root, not the supplied root artifact dir
            Path rootPath = Paths.get(cxt.getRoot().getParentFile().getPath());
            Path filePath = Paths.get(cxt.getFile().getPath());
            artifact.setArtifactRef(String.valueOf(rootPath.relativize(filePath)));
            if (uri.getFragment() != null) {
                artifact.getArtifactRef().setFragment(uri.getFragment());
            }
            /*
             * if file is a normal file, set the isByteStream flag to true on
             * PackageArtifact
             */

            if (cxt.getFile().isFile()) {
                artifact.setByteStream(true);
            }

            artifact.setType(mapping.getType().getValue());

            if (mapping.getType().isByteStream() != null) {
                artifact.setByteStream(mapping.getType().isByteStream());
            } else {
                artifact.setByteStream(cxt.getFile().isFile());
            }

            for (Map.Entry<String, List<String>> entry : mapping
                    .getProperties().entrySet()) {
                Set<String> valueSet = new HashSet<String>(entry.getValue());
                artifact.setSimplePropertyValues(entry.getKey(), valueSet);
            }
            /*
             * Since we use file URI as artifact IDs (with optional specifier as
             * URI fragment), we just need to use the relationship's target
             * file's URI as the relationship target, and we're done!.
             */
            List<PackageRelationship> rels = new ArrayList<PackageRelationship>();

            for (Map.Entry<String, Set<URI>> rel : mapping.getRelationships()
                    .entrySet()) {
                Set<String> relTargets = new HashSet<String>();
                for (URI target : rel.getValue()) {
                    relTargets.add(target.toString());
                }

                rels.add(new PackageRelationship(rel.getKey(), true, relTargets));

            }
            artifact.setRelationships(rels);
        }
    }

}