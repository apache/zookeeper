using System.Collections;
using System.Collections.Generic;
using System.IO;
using org.apache.jute;
using org.apache.utils;
using org.apache.zookeeper.proto;

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

namespace org.apache.zookeeper
{
	/// <summary>
	/// Handles the response from a multi request.  Such a response consists of
	/// a sequence of responses each prefixed by a MultiResponse that indicates
	/// the type of the response.  The end of the list is indicated by a MultiHeader
	/// with a negative type.  Each individual response is in the same format as
	/// with the corresponding operation in the original request list.
	/// </summary>
	internal class MultiResponse : Record, IEnumerable<OpResult>
	{
		private List<OpResult> results = new List<OpResult>();

    public void add(OpResult x) {
        results.Add(x);
    }

    public IEnumerator<OpResult> GetEnumerator()
    {
        return results.GetEnumerator();
    }

        public void serialize(OutputArchive archive, string tag) {

			foreach (OpResult result in results) {
			    ZooDefs.OpCode opcode = EnumUtil<ZooDefs.OpCode>.DefinedCast(result.get_Type());
            int err = result.get_Type() == (int) ZooDefs.OpCode.error ? ((OpResult.ErrorResult)result).getErr() : 0;

            ((Record) new MultiHeader(result.get_Type(), false, err)).serialize(archive, tag);

                switch (opcode)
				{
					case ZooDefs.OpCode.create:
                    ((Record)new CreateResponse(((OpResult.CreateResult) result).getPath())).serialize(archive, tag);
						break;
					case ZooDefs.OpCode.delete:
					case ZooDefs.OpCode.check:
						break;
					case ZooDefs.OpCode.setData:
                    ((Record)new SetDataResponse(((OpResult.SetDataResult) result).getStat())).serialize(archive, tag);
						break;
					case ZooDefs.OpCode.error:
                    ((Record)new ErrorResponse(((OpResult.ErrorResult) result).getErr())).serialize(archive, tag);
						break;
					default:
                    throw new IOException("Invalid type " + result.get_Type() + " in MultiResponse");
				}
			}
			((Record) new MultiHeader(-1, true, -1)).serialize(archive, tag);
		}


		public void deserialize(InputArchive archive, string tag)
		{
            results=new List<OpResult>();
			MultiHeader h = new MultiHeader();
			((Record) h).deserialize(archive, tag);
			while (!h.getDone())
			{
                ZooDefs.OpCode opcode = EnumUtil<ZooDefs.OpCode>.DefinedCast(h.get_Type());
                switch (opcode)
				{
					case ZooDefs.OpCode.create:
						CreateResponse cr = new CreateResponse();
						((Record) cr).deserialize(archive, tag);
						results.Add(new OpResult.CreateResult(cr.getPath()));
						break;

					case ZooDefs.OpCode.delete:
						results.Add(new OpResult.DeleteResult());
						break;

					case ZooDefs.OpCode.setData:
						SetDataResponse sdr = new SetDataResponse();
						((Record) sdr).deserialize(archive, tag);
						results.Add(new OpResult.SetDataResult(sdr.getStat()));
						break;

					case ZooDefs.OpCode.check:
						results.Add(new OpResult.CheckResult());
						break;

					case ZooDefs.OpCode.error:
						//FIXME: need way to more cleanly serialize/deserialize exceptions
						ErrorResponse er = new ErrorResponse();
						((Record) er).deserialize(archive, tag);
						results.Add(new OpResult.ErrorResult(er.getErr()));
						break;

					default:
						throw new IOException("Invalid type " + h.get_Type() + " in MultiResponse");
				}
				((Record) h).deserialize(archive, tag);
			}
		}

    public List<OpResult> getResultList() {
        return results;
    }

	    IEnumerator IEnumerable.GetEnumerator()
	    {
	        return GetEnumerator();
	    }
	}

}