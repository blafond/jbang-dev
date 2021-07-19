///usr/bin/env jbang "$0" "$@" ; exit $?
/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */

//DEPS io.vertx:vertx-mysql-client:${vertx.version:4.1.1}
//DEPS io.vertx:vertx-unit:${vertx.version:4.1.1}
//DEPS org.hibernate.reactive:hibernate-reactive-core:${hibernate-reactive.version:1.0.0.CR7}
//DEPS org.assertj:assertj-core:3.19.0
//DEPS junit:junit:4.13.2
//DEPS org.testcontainers:mysql:1.15.3
//DEPS org.slf4j:slf4j-simple:1.7.30

//// Testcontainer needs the JDBC drivers to start the container
//// Hibernate Reactive doesn't need it
//DEPS mysql:mysql-connector-java:8.0.25

import java.io.Serializable;
import java.time.Duration;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import org.assertj.core.api.Assertions;

import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder;
import org.hibernate.reactive.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;

import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.testcontainers.containers.MySQLContainer;

@RunWith(VertxUnitRunner.class)
public class Issue886 {

	@ClassRule
	public static MySQLContainer<?> database = new MySQLContainer<>( "mysql:8.0.25" );

	private Mutiny.SessionFactory sessionFactory;

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
		configuration.setProperty( Settings.URL, database.getJdbcUrl() );

		// Credentials
		configuration.setProperty( Settings.USER, database.getUsername() );
		configuration.setProperty( Settings.PASS, database.getPassword() );

		// Schema generation. Supported values are create, drop, create-drop, drop-create, none
		configuration.setProperty( Settings.HBM2DDL_AUTO, "create" );

		// Register new entity classes here
		configuration.addAnnotatedClass( SampleEntity.class );
		configuration.addAnnotatedClass( SampleJoinEntity.class );

		// (Optional) Log the SQL queries
		configuration.setProperty( Settings.SHOW_SQL, "true" );
		configuration.setProperty( Settings.HIGHLIGHT_SQL, "true" );
		configuration.setProperty( Settings.FORMAT_SQL, "true" );
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
		StandardServiceRegistryBuilder builder = new ReactiveServiceRegistryBuilder()
				.applySettings( configuration.getProperties() );
		StandardServiceRegistry registry = builder.build();

		sessionFactory = configuration.buildSessionFactory( registry )
				.unwrap( Mutiny.SessionFactory.class );
	}

	@Test
	public void testInsertSelectAndUpdate(TestContext context) {
		final String ORIGINAL_FIELD = "test";
		final String UPDATED_FIELD = "updatedTest";

		SampleEntity sampleEntity = new SampleEntity();
		sampleEntity.sampleField = ORIGINAL_FIELD;
		SampleJoinEntity sampleJoinEntity = new SampleJoinEntity();
		sampleJoinEntity.sampleEntity = sampleEntity;

		sessionFactory.withTransaction( (s, t) -> s
				.persist( sampleEntity )
				.call( s::flush )
				.chain( () -> s.find( SampleEntity.class, sampleEntity.id ) )
				.invoke( found -> context.assertEquals( sampleEntity, found ) ) )
				.chain( () -> sessionFactory.withTransaction( (session, transaction) -> session
						.persist( sampleJoinEntity )
						.call( session::flush )
						.chain( () -> session.find( SampleJoinEntity.class, sampleJoinEntity.id ) )
						.invoke( found -> context.assertEquals( sampleJoinEntity, found )
						) )
				)
				.chain( result -> sessionFactory.withStatelessTransaction(
						(s, t) -> {
							context.assertFalse( result instanceof HibernateProxy );
							result.sampleEntity.sampleField = UPDATED_FIELD;
							return s.withTransaction( tx -> s.update( result.sampleEntity )
									.chain( () -> s.refresh( result.sampleEntity )));
						} )
				).await().indefinitely();
	}

	@After
	public void closeFactory() {
		if ( sessionFactory != null ) {
			sessionFactory.close();
		}
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

		Result result = JUnitCore.runClasses( Issue886.class );

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