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
package org.apache.nifi.processors.box;

import com.box.sdkgen.schemas.filefull.FileFull;
import com.box.sdkgen.schemas.folderfull.FolderFull;
import com.box.sdkgen.schemas.foldermini.FolderMini;
import org.apache.nifi.flowfile.attributes.CoreAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.valueOf;
import static java.util.stream.Collectors.joining;

public final class BoxFileUtils {

    public static final String BOX_URL = "https://app.box.com/file/";

    public static String getParentIds(final FileFull info) {
        return getParentIdsFromEntries(info.getPathCollection().getEntries());
    }

    public static String getParentPath(final FileFull info) {
        return getParentPathFromEntries(info.getPathCollection().getEntries());
    }

    public static String getParentPath(final FolderFull info) {
        return getParentPathFromEntries(info.getPathCollection().getEntries());
    }

    public static String getFolderPath(FolderFull folderInfo) {
        final String parentFolderPath = getParentPath(folderInfo);
        return "/".equals(parentFolderPath) ? parentFolderPath + folderInfo.getName() : parentFolderPath + "/" + folderInfo.getName();
    }

    public static Map<String, String> createAttributeMap(FileFull fileInfo) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BoxFileAttributes.ID, fileInfo.getId());
        attributes.put(CoreAttributes.FILENAME.key(), fileInfo.getName());
        attributes.put(CoreAttributes.PATH.key(), getParentPath(fileInfo));
        attributes.put(BoxFileAttributes.TIMESTAMP, valueOf(fileInfo.getModifiedAt()));
        attributes.put(BoxFileAttributes.SIZE, valueOf(fileInfo.getSize()));
        return attributes;
    }

    private static String getParentIdsFromEntries(final List<FolderMini> entries) {
        return entries.stream()
                .map(FolderMini::getId)
                .collect(joining(","));
    }

    private static String getParentPathFromEntries(final List<FolderMini> entries) {
        return "/" + entries.stream()
                .filter(entry -> !"0".equals(entry.getId()))
                .map(FolderMini::getName)
                .collect(joining("/"));
    }
}
