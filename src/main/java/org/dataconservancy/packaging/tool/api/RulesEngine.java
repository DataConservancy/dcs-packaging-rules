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

package org.dataconservancy.packaging.tool.api;

import org.apache.jena.rdf.model.Model;

import java.io.File;

/**
 * Generates a jena model based on the contents
 * of a directory tree and a rules file.
 *
 * Created by jrm on 9/2/16.
 */
public interface RulesEngine {
    Model generateRdf(File directoryTreeRoot) throws RulesEngineException;
}


