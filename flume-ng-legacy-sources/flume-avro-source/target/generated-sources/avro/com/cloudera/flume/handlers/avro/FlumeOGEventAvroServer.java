/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.cloudera.flume.handlers.avro;

@SuppressWarnings("all")
/** * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License. */
@org.apache.avro.specific.AvroGenerated
public interface FlumeOGEventAvroServer {
  public static final org.apache.avro.Protocol PROTOCOL = org.apache.avro.Protocol.parse("{\"protocol\":\"FlumeOGEventAvroServer\",\"namespace\":\"com.cloudera.flume.handlers.avro\",\"doc\":\"* Licensed to the Apache Software Foundation (ASF) under one\\n * or more contributor license agreements.  See the NOTICE file\\n * distributed with this work for additional information\\n * regarding copyright ownership.  The ASF licenses this file\\n * to you under the Apache License, Version 2.0 (the\\n * \\\"License\\\"); you may not use this file except in compliance\\n * with the License.  You may obtain a copy of the License at\\n *\\n * http://www.apache.org/licenses/LICENSE-2.0\\n *\\n * Unless required by applicable law or agreed to in writing,\\n * software distributed under the License is distributed on an\\n * \\\"AS IS\\\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\\n * KIND, either express or implied.  See the License for the\\n * specific language governing permissions and limitations\\n * under the License.\",\"types\":[{\"type\":\"enum\",\"name\":\"Priority\",\"symbols\":[\"FATAL\",\"ERROR\",\"WARN\",\"INFO\",\"DEBUG\",\"TRACE\"]},{\"type\":\"record\",\"name\":\"AvroFlumeOGEvent\",\"fields\":[{\"name\":\"timestamp\",\"type\":\"long\"},{\"name\":\"priority\",\"type\":\"Priority\"},{\"name\":\"body\",\"type\":\"bytes\"},{\"name\":\"nanos\",\"type\":\"long\"},{\"name\":\"host\",\"type\":\"string\"},{\"name\":\"fields\",\"type\":{\"type\":\"map\",\"values\":\"bytes\"}}]}],\"messages\":{\"append\":{\"request\":[{\"name\":\"evt\",\"type\":\"AvroFlumeOGEvent\"}],\"response\":\"null\"}}}");
  java.lang.Void append(com.cloudera.flume.handlers.avro.AvroFlumeOGEvent evt) throws org.apache.avro.AvroRemoteException;

  @SuppressWarnings("all")
  /** * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License. */
  public interface Callback extends FlumeOGEventAvroServer {
    public static final org.apache.avro.Protocol PROTOCOL = com.cloudera.flume.handlers.avro.FlumeOGEventAvroServer.PROTOCOL;
    void append(com.cloudera.flume.handlers.avro.AvroFlumeOGEvent evt, org.apache.avro.ipc.Callback<java.lang.Void> callback) throws java.io.IOException;
  }
}