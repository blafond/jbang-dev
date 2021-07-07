///usr/bin/env jbang "$0" "$@" ; exit $?
/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */

//DEPS io.vertx:vertx-mysql-client:${vertx.version:4.1.1}
//DEPS io.vertx:vertx-unit:${vertx.version:4.1.1}
//DEPS org.hibernate:hibernate-core:5.5.2.Final
//DEPS org.assertj:assertj-core:3.19.0
//DEPS junit:junit:4.13.2
//DEPS org.testcontainers:mysql:1.15.3
//DEPS org.slf4j:slf4j-simple:1.7.30

//// Testcontainer needs the JDBC drivers to start the container
//// Hibernate Reactive doesn't need it
//DEPS mysql:mysql-connector-java:8.0.25

import static org.junit.Assert.assertEquals;

import java.io.Serializable;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.AvailableSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;

import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.testcontainers.containers.MySQLContainer;

@RunWith(VertxUnitRunner.class)
public class Issue886WithORM {

	@ClassRule
	public static MySQLContainer<?> database = new MySQLContainer<>( "mysql:8.0.25" );

	private SessionFactory ormFactory;

	@BeforeClass
	public static void startContainer() {
		database.start();
	}

	/**
	 * The {@link Configuration} for the {@link Mutiny.SessionFactory}.
	 */
	private Configuration createConfiguration() {
		Configuration configuration = new Configuration();

		// JDBC url
		configuration.setProperty( AvailableSettings.URL, database.getJdbcUrl() );

		// Credentials
		configuration.setProperty( AvailableSettings.USER, database.getUsername() );
		configuration.setProperty( AvailableSettings.PASS, database.getPassword() );

		// Schema generation. Supported values are create, drop, create-drop, drop-create, none
		configuration.setProperty( AvailableSettings.HBM2DDL_AUTO, "create" );

		// Register new entity classes here
		configuration.addAnnotatedClass( SampleEntity.class );
		configuration.addAnnotatedClass( SampleJoinEntity.class );

		// (Optional) Log the SQL queries
		configuration.setProperty( AvailableSettings.SHOW_SQL, "true" );
		configuration.setProperty( AvailableSettings.HIGHLIGHT_SQL, "true" );
		configuration.setProperty( AvailableSettings.FORMAT_SQL, "true" );
		return configuration;
	}

	/*
	 * Create a new factory and a new schema before each test (see
	 * property `hibernate.hbm2ddl.auto`).
	 * This way each test will start with a clean database.
	 *
	 * The drawback is that, in a real case scenario with multiple tests,
	 * it can slow down the whole test suite considerably. If that happens,
	 * it's possible to make the session factory static and, if necessary,
	 * delete the content of the tables manually (without dropping them).
	 */
	@Before
	public void createSessionFactory() {
		Configuration configuration = createConfiguration();
		StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder()
				.applySettings( configuration.getProperties() );
		StandardServiceRegistry registry = builder.build();

		ormFactory = configuration.buildSessionFactory( registry );
	}

	@Test
	public void testJoinInsertWithSessionUpdate() {
		SampleEntity sampleEntity = new SampleEntity();
		sampleEntity.sampleField = "test";

		Session session = ormFactory.openSession();
		session.beginTransaction();
		session.persist( sampleEntity );
		session.getTransaction().commit();
		session.close();

		SampleJoinEntity sampleJoinEntity = new SampleJoinEntity();
		sampleJoinEntity.sampleEntity = sampleEntity;

		session = ormFactory.openSession();
		session.beginTransaction();
		session.persist( sampleJoinEntity );
		session.getTransaction().commit();
		session.close();

		Long targetId = sampleJoinEntity.id;

		session = ormFactory.openSession();
		session.beginTransaction();
		List results  = session.createQuery("from SampleJoinEntity WHERE id = " + targetId).list();
		assertEquals(1, results.size());
		SampleJoinEntity sampleJoinEntityFromDatabase = ((SampleJoinEntity) results.get(0));
		session.getTransaction().commit();
		session.close();

		SampleEntity sampleEntityFromDatabase = sampleJoinEntityFromDatabase.sampleEntity;
		sampleEntityFromDatabase.sampleField = "test";

		session = ormFactory.openSession();
		session.beginTransaction();
		session.update(sampleEntityFromDatabase);
		session.getTransaction().commit();
		session.close();
	}

	@Test
	public void testJoinWithStatelessSessionUpdate() {
		SampleEntity sampleEntity = new SampleEntity();
		sampleEntity.sampleField = "test";

		Session session = ormFactory.openSession();
		session.beginTransaction();
		session.persist( sampleEntity );
		session.getTransaction().commit();
		session.close();

		SampleJoinEntity sampleJoinEntity = new SampleJoinEntity();
		sampleJoinEntity.sampleEntity = sampleEntity;

		session = ormFactory.openSession();
		session.beginTransaction();
		session.persist( sampleJoinEntity );
		session.getTransaction().commit();
		session.close();

		Long targetId = sampleJoinEntity.id;

		session = ormFactory.openSession();
		session.beginTransaction();
		List results  = session.createQuery("from SampleJoinEntity WHERE id = " + targetId).list();
		assertEquals(1, results.size());
		SampleJoinEntity sampleJoinEntityFromDatabase = ((SampleJoinEntity) results.get(0));
		session.getTransaction().commit();
		session.close();

		SampleEntity sampleEntityFromDatabase = sampleJoinEntityFromDatabase.sampleEntity;
		sampleEntityFromDatabase.sampleField = "test";

// EXCEPTION IS HERE!
		StatelessSession statelessSession = ormFactory.openStatelessSession();
		statelessSession.beginTransaction();
		statelessSession.update(sampleEntityFromDatabase);
		statelessSession.getTransaction().commit();
		statelessSession.close();
	}

	@Entity(name = "SampleEntity")
	@Table(name = "sample_entities")
	public static class SampleEntity implements Serializable {
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		public Long id;

		@Column(name = "sample_field")
		public String sampleField;
	}

	@Entity(name = "SampleJoinEntity")
	@Table(name = "sample_join_entities")
	public static class SampleJoinEntity implements Serializable {
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		public Long id;

		@ManyToOne(fetch = FetchType.LAZY)
		@JoinColumn(name = "sample_entity_id", referencedColumnName = "id")
		public SampleEntity sampleEntity;
	}

	// This main class is only for JBang so that it can run the tests with `jbang Issue886.java`
	public static void main(String[] args) {
		System.out.println( "Starting the test suite with MySQL" );

		Result result = JUnitCore.runClasses( Issue886WithORM.class );

		for ( Failure failure : result.getFailures() ) {
			System.out.println();
			System.err.println( "Test " + failure.getTestHeader() + " FAILED!" );
			System.err.println( "\t" + failure.getTrace() );
		}

		System.out.println();
		System.out.print( "Tests result summary: " );
		System.out.println( result.wasSuccessful() ? "SUCCESS" : "FAILURE" );
	}
}
