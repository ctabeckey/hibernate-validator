/*
* JBoss, Home of Professional Open Source
* Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.internal.metadata;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import javax.validation.ParameterNameProvider;

import org.hibernate.validator.internal.engine.DefaultParameterNameProvider;
import org.hibernate.validator.internal.metadata.aggregated.BeanMetaData;
import org.hibernate.validator.internal.metadata.aggregated.BeanMetaDataImpl;
import org.hibernate.validator.internal.metadata.aggregated.BeanMetaDataImpl.BeanMetaDataBuilder;
import org.hibernate.validator.internal.metadata.core.AnnotationProcessingOptions;
import org.hibernate.validator.internal.metadata.core.AnnotationProcessingOptionsImpl;
import org.hibernate.validator.internal.metadata.core.ConstraintHelper;
import org.hibernate.validator.internal.metadata.provider.AnnotationMetaDataProvider;
import org.hibernate.validator.internal.metadata.provider.MetaDataProvider;
import org.hibernate.validator.internal.metadata.raw.BeanConfiguration;
import org.hibernate.validator.internal.util.ConcurrentReferenceHashMap;
import org.hibernate.validator.internal.util.Contracts;
import org.hibernate.validator.internal.util.ExecutableHelper;

import static org.hibernate.validator.internal.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.internal.util.ConcurrentReferenceHashMap.Option.IDENTITY_COMPARISONS;
import static org.hibernate.validator.internal.util.ConcurrentReferenceHashMap.ReferenceType.SOFT;
import static org.hibernate.validator.internal.util.logging.Messages.MESSAGES;

/**
 * This manager is in charge of providing all constraint related meta data
 * required by the validation engine.
 * <p>
 * Actual retrieval of meta data is delegated to {@link MetaDataProvider}
 * implementations which load meta-data based e.g. based on annotations or XML.
 * </p>
 * <p>
 * For performance reasons a cache is used which stores all meta data once
 * loaded for repeated retrieval. Upon initialization this cache is populated
 * with meta data provided by the given <i>eager</i> providers. If the cache
 * doesn't contain the meta data for a requested type it will be retrieved on
 * demand using the annotation based provider.
 * </p>
 *
 * @author Gunnar Morling
 * @author Chris Beckey <cbeckey@paypal.com> (C) 2014 ebay, Inc.
*/
public class BeanMetaDataManager {
	/**
	 * The default initial capacity for this cache.
	 */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;

	/**
	 * The default load factor for this cache.
	 */
	private static final float DEFAULT_LOAD_FACTOR = 0.75f;

	/**
	 * The default concurrency level for this cache.
	 */
	private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

	/**
	 * Additional metadata providers used for meta data retrieval if
	 * the XML and/or programmatic configuration is used.
	 */
	private final List<MetaDataProvider> metaDataProviders;

	/**
	 * Helper for builtin constraints and their validator implementations
	 */
	private final ConstraintHelper constraintHelper;

	/**
	 * Used to cache the constraint meta data for validated entities
	 */
	private final ConcurrentReferenceHashMap<Class<?>, BeanMetaData<?>> beanMetaDataCache;

	/**
	 * Used for resolving type parameters. Thread-safe.
	 */
	private final ExecutableHelper executableHelper;

	/**
	 * These three fields affect the invocation of rules associated to section 4.5.5
	 * of the V1.1 specification.  By default they are all false, if true they allow
	 * for relaxation of the Liskov Substitution Principal.
	 */
	private final boolean allowOverridingMethodAlterParameterConstraint; 
	private final boolean allowParallelMethodsDefineGroupConversion;
	private final boolean allowParallelMethodsDefineParameterConstraints;
	
	/**
	 * Creates a new {@code BeanMetaDataManager}. {@link DefaultParameterNameProvider} is used as parameter name
	 * provider, no meta data providers besides the annotation-based providers are used.
	 *
	 * @param constraintHelper the constraint helper
	 * @param executableHelper the executable helper
	 */
	public BeanMetaDataManager(ConstraintHelper constraintHelper, ExecutableHelper executableHelper) {
		this( constraintHelper, executableHelper, new DefaultParameterNameProvider(), Collections.<MetaDataProvider>emptyList() );
	}

	/**
	 * Creates a new {@code BeanMetaDataManager}.
	 *
	 * @param constraintHelper the constraint helper
	 * @param executableHelper the executable helper
	 * @param parameterNameProvider the parameter name provider
	 * @param optionalMetaDataProviders optional meta data provider used on top of the annotation based provider
	 */
	public BeanMetaDataManager(ConstraintHelper constraintHelper,
			   ExecutableHelper executableHelper,
			   ParameterNameProvider parameterNameProvider,
			   List<MetaDataProvider> optionalMetaDataProviders) {
		this(constraintHelper, executableHelper, parameterNameProvider, optionalMetaDataProviders, false, false, false);
	}
	
	public BeanMetaDataManager(ConstraintHelper constraintHelper,
			ExecutableHelper executableHelper,
			ParameterNameProvider parameterNameProvider,
			List<MetaDataProvider> optionalMetaDataProviders,
			boolean allowOverridingMethodAlterParameterConstraint, 
			boolean allowParallelMethodsDefineGroupConversion,
			boolean allowParallelMethodsDefineParameterConstraints) {
		this.constraintHelper = constraintHelper;
		this.metaDataProviders = newArrayList();
		this.metaDataProviders.addAll( optionalMetaDataProviders );
		this.executableHelper = executableHelper;

		this.allowOverridingMethodAlterParameterConstraint = allowOverridingMethodAlterParameterConstraint;
		this.allowParallelMethodsDefineGroupConversion = allowParallelMethodsDefineGroupConversion;
		this.allowParallelMethodsDefineParameterConstraints = allowParallelMethodsDefineParameterConstraints;
		
		this.beanMetaDataCache = new ConcurrentReferenceHashMap<Class<?>, BeanMetaData<?>>(
				DEFAULT_INITIAL_CAPACITY,
				DEFAULT_LOAD_FACTOR,
				DEFAULT_CONCURRENCY_LEVEL,
				SOFT,
				SOFT,
				EnumSet.of( IDENTITY_COMPARISONS )
		);


		AnnotationProcessingOptions annotationProcessingOptions = getAnnotationProcessingOptionsFromNonDefaultProviders();
		AnnotationMetaDataProvider defaultProvider = new AnnotationMetaDataProvider(
				constraintHelper,
				parameterNameProvider,
				annotationProcessingOptions
		);
		this.metaDataProviders.add( defaultProvider );
	}

	@SuppressWarnings("unchecked")
	public <T> BeanMetaData<T> getBeanMetaData(Class<T> beanClass) {
		Contracts.assertNotNull( beanClass, MESSAGES.beanTypeCannotBeNull() );

		BeanMetaData<T> beanMetaData = (BeanMetaData<T>) beanMetaDataCache.get( beanClass );

		// create a new BeanMetaData in case none is cached
		if ( beanMetaData == null ) {
			beanMetaData = createBeanMetaData( beanClass );

			final BeanMetaData<T> cachedBeanMetaData = (BeanMetaData<T>) beanMetaDataCache.putIfAbsent(
					beanClass,
					beanMetaData
			);
			if ( cachedBeanMetaData != null ) {
				beanMetaData = cachedBeanMetaData;
			}
		}

		return beanMetaData;
	}

	public void clear() {
		beanMetaDataCache.clear();
	}

	public int numberOfCachedBeanMetaDataInstances() {
		return beanMetaDataCache.size();
	}

	/**
	 * Creates a {@link org.hibernate.validator.internal.metadata.aggregated.BeanMetaData} containing the meta data from all meta
	 * data providers for the given type and its hierarchy.
	 *
	 * @param <T> The type of interest.
	 * @param clazz The type's class.
	 *
	 * @return A bean meta data object for the given type.
	 */
	private <T> BeanMetaDataImpl<T> createBeanMetaData(Class<T> clazz) {
		BeanMetaDataBuilder<T> builder = BeanMetaDataBuilder.getInstance( 
				constraintHelper, executableHelper, clazz, 
				allowOverridingMethodAlterParameterConstraint,
				allowParallelMethodsDefineGroupConversion,
				allowParallelMethodsDefineParameterConstraints);

		for ( MetaDataProvider provider : metaDataProviders ) {
			for ( BeanConfiguration<? super T> beanConfiguration : provider.getBeanConfigurationForHierarchy( clazz ) ) {
				builder.add( beanConfiguration );
			}
		}

		return builder.build();
	}

	/**
	 * @return returns the annotation ignores from the non annotation based meta data providers
	 */
	private AnnotationProcessingOptions getAnnotationProcessingOptionsFromNonDefaultProviders() {
		AnnotationProcessingOptions options = new AnnotationProcessingOptionsImpl();
		for ( MetaDataProvider metaDataProvider : metaDataProviders ) {
			options.merge( metaDataProvider.getAnnotationProcessingOptions() );
		}

		return options;
	}
}
