/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johnpoth;

public enum ImageNamingStrategy {

    /** By default, Maven resources will be lower cased, "/", "." will be replaced with "_" when constructing image repository names e.g:
     *
     *  org/apache/ant/ant/1.10.11/ant-1.10.11.jar will be give an image repository name of:
     *
     *  org_apache_ant_ant_1_10_11_ant-1_10_11_jar
     *
     * */
    Default,


    /**
     * Maven resources will be sha256 e.g
     *
     * org/apache/ant/ant/1.10.11/ant-1.10.11.jar will be give an image repository name of:
     *
     * d3a7e571f0f47d4cdef81c71e1b97c9b7806f8d04b2838d80b6661ee017c465e
     *
     * WARNING : This should be used as a last resort as there is no way of telling what's in the image by looking at it's name
     */
    SHA256,

    /** None won't change the anything e.g:
     *
     *  org/apache/ant/ant/1.10.11/ant-1.10.11.jar will be give an image repository name of:
     *
     *  org/apache/ant/ant/1.10.11/ant-1.10.11.jar
     * */
    None
}
