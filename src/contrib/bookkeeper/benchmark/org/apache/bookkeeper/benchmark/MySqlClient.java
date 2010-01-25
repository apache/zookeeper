package org.apache.bookkeeper.benchmark;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.QuorumEngine;
import org.apache.log4j.Logger;


import org.apache.zookeeper.KeeperException;

public class MySqlClient {
	static Logger LOG = Logger.getLogger(QuorumEngine.class);

	BookKeeper x;
	LedgerHandle lh;
	Integer entryId;
	HashMap<Integer, Integer> map;

	FileOutputStream fStream;
	FileOutputStream fStreamLocal;
	long start, lastId;
	Connection con;
	Statement stmt;
	
	
	public MySqlClient(String hostport, String user, String pass) 
			throws ClassNotFoundException {
		entryId = 0;
		map = new HashMap<Integer, Integer>();
		Class.forName("com.mysql.jdbc.Driver");
		// database is named "bookkeeper"
		String url = "jdbc:mysql://" + hostport + "/bookkeeper";
		try {
			con = DriverManager.getConnection(url, user, pass);
			stmt = con.createStatement();
			// drop table and recreate it
			stmt.execute("DROP TABLE IF EXISTS data;");
			stmt.execute("create table data(transaction_id bigint PRIMARY KEY AUTO_INCREMENT, content TEXT);");
			LOG.info("Database initialization terminated");
		} catch (SQLException e) {
			
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void closeHandle() throws KeeperException, InterruptedException, SQLException{
		con.close();
	}
	/**
	 * First parameter is an integer defining the length of the message 
	 * Second parameter is the number of writes
	 * Third parameter is host:port 
	 * Fourth parameter is username
	 * Fifth parameter is password
	 * @param args
	 * @throws ClassNotFoundException 
	 * @throws SQLException 
	 */
	public static void main(String[] args) throws ClassNotFoundException, SQLException {		
		int lenght = Integer.parseInt(args[1]);
		StringBuilder sb = new StringBuilder();
		while(lenght-- > 0){
			sb.append('a');
		}
		try {
			MySqlClient c = new MySqlClient(args[2], args[3], args[4]);
			c.writeSameEntryBatch(sb.toString().getBytes(), Integer.parseInt(args[0]));
			c.writeSameEntry(sb.toString().getBytes(), Integer.parseInt(args[0]));
			c.closeHandle();
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (KeeperException e) {
			e.printStackTrace();
		} 

	}

	/**	
	 * 	Adds  data entry to the DB 
	 * 	@param data 	the entry to be written, given as a byte array 
	 * 	@param times	the number of times the entry should be written on the DB	*/
	void writeSameEntryBatch(byte[] data, int times) throws InterruptedException, SQLException{
		start = System.currentTimeMillis();
		int count = times;
		String content = new String(data);
		System.out.println("Data: " + content + ", " + data.length);
		while(count-- > 0){
			stmt.addBatch("insert into data(content) values(\"" + content + "\");");
		}
		LOG.info("Finished writing batch SQL command in ms: " + (System.currentTimeMillis() - start));
		start = System.currentTimeMillis();
		stmt.executeBatch();
		System.out.println("Finished " + times + " writes in ms: " + (System.currentTimeMillis() - start));       
		LOG.info("Ended computation");
	}

	void writeSameEntry(byte[] data, int times) throws InterruptedException, SQLException{
		start = System.currentTimeMillis();
		int count = times;
		String content = new String(data);
		System.out.println("Data: " + content + ", " + data.length);
		while(count-- > 0){
			stmt.executeUpdate("insert into data(content) values(\"" + content + "\");");
		}
		System.out.println("Finished " + times + " writes in ms: " + (System.currentTimeMillis() - start));       
		LOG.info("Ended computation");
	}

}