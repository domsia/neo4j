/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.batchinsert.internal;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.LongFunction;

import org.neo4j.collection.primitive.PrimitiveIntCollections;
import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.schema.ConstraintCreator;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexCreator;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.IteratorWrapper;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.helpers.collection.Visitor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracerSupplier;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.index.IndexEntryUpdate;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.index.NodeUpdates;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.api.labelscan.LabelScanWriter;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdate;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.schema_new.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema_new.SchemaDescriptorFactory;
import org.neo4j.kernel.api.schema_new.constaints.ConstraintDescriptor;
import org.neo4j.kernel.api.schema_new.constaints.ConstraintDescriptorFactory;
import org.neo4j.kernel.api.schema_new.constaints.UniquenessConstraintDescriptor;
import org.neo4j.kernel.api.schema_new.index.NewIndexDescriptor;
import org.neo4j.kernel.api.schema_new.index.NewIndexDescriptorFactory;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.extension.KernelExtensions;
import org.neo4j.kernel.extension.UnsatisfiedDependencyStrategies;
import org.neo4j.kernel.extension.dependency.HighestSelectionStrategy;
import org.neo4j.kernel.extension.dependency.NamedLabelScanStoreSelectionStrategy;
import org.neo4j.kernel.impl.api.index.SchemaIndexProviderMap;
import org.neo4j.kernel.impl.api.index.StoreScan;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.scan.LabelScanStoreProvider;
import org.neo4j.kernel.impl.api.store.SchemaCache;
import org.neo4j.kernel.impl.constraints.StandardConstraintSemantics;
import org.neo4j.kernel.impl.core.RelationshipTypeToken;
import org.neo4j.kernel.impl.coreapi.schema.BaseNodeConstraintCreator;
import org.neo4j.kernel.impl.coreapi.schema.IndexCreatorImpl;
import org.neo4j.kernel.impl.coreapi.schema.IndexDefinitionImpl;
import org.neo4j.kernel.impl.coreapi.schema.InternalSchemaActions;
import org.neo4j.kernel.impl.coreapi.schema.NodePropertyExistenceConstraintDefinition;
import org.neo4j.kernel.impl.coreapi.schema.RelationshipPropertyExistenceConstraintDefinition;
import org.neo4j.kernel.impl.coreapi.schema.UniquenessConstraintDefinition;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.NoOpClient;
import org.neo4j.kernel.impl.logging.StoreLogService;
import org.neo4j.kernel.impl.pagecache.ConfiguringPageCacheFactory;
import org.neo4j.kernel.impl.pagecache.PageCacheLifecycle;
import org.neo4j.kernel.impl.spi.SimpleKernelContext;
import org.neo4j.kernel.impl.store.CountsComputer;
import org.neo4j.kernel.impl.store.LabelTokenStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.NodeLabels;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.PropertyKeyTokenStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.RecordCursors;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.RelationshipTypeTokenStore;
import org.neo4j.kernel.impl.store.SchemaStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.counts.CountsTracker;
import org.neo4j.kernel.impl.store.format.RecordFormatSelector;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.id.DefaultIdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.validation.IdValidator;
import org.neo4j.kernel.impl.store.record.ConstraintRule;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.IndexRule;
import org.neo4j.kernel.impl.store.record.LabelTokenRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.PrimitiveRecord;
import org.neo4j.kernel.impl.store.record.PropertyBlock;
import org.neo4j.kernel.impl.store.record.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.kernel.impl.store.record.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.transaction.state.DefaultSchemaIndexProviderMap;
import org.neo4j.kernel.impl.transaction.state.PropertyCreator;
import org.neo4j.kernel.impl.transaction.state.PropertyDeleter;
import org.neo4j.kernel.impl.transaction.state.PropertyTraverser;
import org.neo4j.kernel.impl.transaction.state.RecordAccess;
import org.neo4j.kernel.impl.transaction.state.RecordAccess.RecordProxy;
import org.neo4j.kernel.impl.transaction.state.RelationshipCreator;
import org.neo4j.kernel.impl.transaction.state.RelationshipGroupGetter;
import org.neo4j.kernel.impl.transaction.state.storeview.NeoStoreIndexStoreView;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.impl.util.Listener;
import org.neo4j.kernel.internal.EmbeddedGraphDatabase;
import org.neo4j.kernel.internal.StoreLocker;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.NullLog;
import org.neo4j.storageengine.api.Token;
import org.neo4j.storageengine.api.schema.SchemaRule;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchRelationship;
import org.neo4j.unsafe.batchinsert.DirectRecordAccessSet;

import static java.lang.Boolean.parseBoolean;
import static org.neo4j.collection.primitive.PrimitiveLongCollections.map;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.kernel.api.index.NodeUpdates.PropertyLoader.NO_UNCHANGED_PROPERTIES;
import static org.neo4j.kernel.impl.store.NodeLabelsField.parseLabelsField;
import static org.neo4j.kernel.impl.store.PropertyStore.encodeString;
import static org.neo4j.kernel.impl.util.IoPrimitiveUtils.safeCastLongToInt;

public class BatchInserterImpl implements BatchInserter, IndexConfigStoreProvider
{
    private final LifeSupport life;
    private final NeoStores neoStores;
    private final IndexConfigStore indexStore;
    private final File storeDir;
    private final BatchTokenHolder propertyKeyTokens;
    private final BatchTokenHolder relationshipTypeTokens;
    private final BatchTokenHolder labelTokens;
    private final IdGeneratorFactory idGeneratorFactory;
    private final SchemaIndexProviderMap schemaIndexProviders;
    private final LabelScanStore labelScanStore;
    private final Log msgLog;
    private final SchemaCache schemaCache;
    private final Config config;
    private final BatchInserterImpl.BatchSchemaActions actions;
    private final StoreLocker storeLocker;
    private boolean labelsTouched;
    private boolean isShutdown;

    private final LongFunction<Label> labelIdToLabelFunction = new LongFunction<Label>()
    {
        @Override
        public Label apply( long from )
        {
            return label( labelTokens.byId( safeCastLongToInt( from ) ).name() );
        }
    };

    private final FlushStrategy flushStrategy;
    // Helper structure for setNodeProperty
    private final RelationshipCreator relationshipCreator;
    private final DirectRecordAccessSet recordAccess;
    private final PropertyTraverser propertyTraverser;
    private final PropertyCreator propertyCreator;
    private final PropertyDeleter propertyDeletor;

    private final NodeStore nodeStore;
    private final RelationshipStore relationshipStore;
    private final RelationshipTypeTokenStore relationshipTypeTokenStore;
    private final PropertyKeyTokenStore propertyKeyTokenStore;
    private final PropertyStore propertyStore;
    private final RecordStore<RelationshipGroupRecord> relationshipGroupStore;
    private final SchemaStore schemaStore;
    private final NeoStoreIndexStoreView indexStoreView;

    private final LabelTokenStore labelTokenStore;
    private final Locks.Client noopLockClient = new NoOpClient();
    private final long maxNodeId;
    private final RecordCursors cursors;

    public BatchInserterImpl( final File storeDir, final FileSystemAbstraction fileSystem,
                       Map<String, String> stringParams, Iterable<KernelExtensionFactory<?>> kernelExtensions ) throws IOException
    {
        rejectAutoUpgrade( stringParams );
        Map<String, String> params = getDefaultParams();
        params.putAll( stringParams );
        this.config = Config.embeddedDefaults( params );

        life = new LifeSupport();
        this.storeDir = storeDir;
        ConfiguringPageCacheFactory pageCacheFactory = new ConfiguringPageCacheFactory(
                fileSystem, config, PageCacheTracer.NULL, PageCursorTracerSupplier.NULL, NullLog.getInstance() );
        PageCache pageCache = pageCacheFactory.getOrCreatePageCache();
        life.add( new PageCacheLifecycle( pageCache ) );

        StoreLogService logService = life.add( StoreLogService.inLogsDirectory( fileSystem, this.storeDir ) );
        msgLog = logService.getInternalLog( getClass() );
        storeLocker = new StoreLocker( fileSystem );
        storeLocker.checkLock( this.storeDir );

        boolean dump = config.get( GraphDatabaseSettings.dump_configuration );
        this.idGeneratorFactory = new DefaultIdGeneratorFactory( fileSystem );

        LogProvider internalLogProvider = logService.getInternalLogProvider();
        RecordFormats recordFormats = RecordFormatSelector.selectForStoreOrConfig( config, storeDir, fileSystem,
                pageCache, internalLogProvider );
        StoreFactory sf = new StoreFactory( this.storeDir, config, idGeneratorFactory, pageCache, fileSystem,
                recordFormats, internalLogProvider );

        maxNodeId = recordFormats.node().getMaxId();

        if ( dump )
        {
            dumpConfiguration( params, System.out );
        }
        msgLog.info( Thread.currentThread() + " Starting BatchInserter(" + this + ")" );
        life.start();
        neoStores = sf.openAllNeoStores( true );
        neoStores.verifyStoreOk();

        nodeStore = neoStores.getNodeStore();
        relationshipStore = neoStores.getRelationshipStore();
        relationshipTypeTokenStore = neoStores.getRelationshipTypeTokenStore();
        propertyKeyTokenStore = neoStores.getPropertyKeyTokenStore();
        propertyStore = neoStores.getPropertyStore();
        relationshipGroupStore = neoStores.getRelationshipGroupStore();
        schemaStore = neoStores.getSchemaStore();
        labelTokenStore = neoStores.getLabelTokenStore();

        List<Token> indexes = propertyKeyTokenStore.getTokens( 10000 );
        propertyKeyTokens = new BatchTokenHolder( indexes );
        labelTokens = new BatchTokenHolder( labelTokenStore.getTokens( Integer.MAX_VALUE ) );
        List<RelationshipTypeToken> types = relationshipTypeTokenStore.getTokens( Integer.MAX_VALUE );
        relationshipTypeTokens = new BatchTokenHolder( types );
        indexStore = life.add( new IndexConfigStore( this.storeDir, fileSystem ) );
        schemaCache = new SchemaCache( new StandardConstraintSemantics(), schemaStore );

        indexStoreView = new NeoStoreIndexStoreView( LockService.NO_LOCK_SERVICE, neoStores );

        Dependencies deps = new Dependencies();
        deps.satisfyDependencies( fileSystem, config, logService, indexStoreView, pageCache );

        KernelExtensions extensions = life.add( new KernelExtensions(
                new SimpleKernelContext( storeDir, DatabaseInfo.UNKNOWN, deps ),
                kernelExtensions, deps, UnsatisfiedDependencyStrategies.ignore() ) );

        SchemaIndexProvider provider = extensions.resolveDependency( SchemaIndexProvider.class,
                HighestSelectionStrategy.getInstance() );
        schemaIndexProviders = new DefaultSchemaIndexProviderMap( provider );
        labelScanStore = life.add( extensions.resolveDependency( LabelScanStoreProvider.class,
                new NamedLabelScanStoreSelectionStrategy( config ) ).getLabelScanStore() );
        actions = new BatchSchemaActions();

        // Record access
        recordAccess = new DirectRecordAccessSet( neoStores );
        relationshipCreator = new RelationshipCreator(
                new RelationshipGroupGetter( relationshipGroupStore ), relationshipGroupStore.getStoreHeaderInt() );
        propertyTraverser = new PropertyTraverser();
        propertyCreator = new PropertyCreator( propertyStore, propertyTraverser );
        propertyDeletor = new PropertyDeleter( propertyTraverser );

        flushStrategy = new BatchedFlushStrategy( recordAccess, config.get( GraphDatabaseSettings
                .batch_inserter_batch_size ) );
        cursors = new RecordCursors( neoStores );
    }

    private Map<String, String> getDefaultParams()
    {
        Map<String, String> params = new HashMap<>();
        params.put( GraphDatabaseSettings.pagecache_memory.name(), "32m" );
        return params;
    }

    @Override
    public boolean nodeHasProperty( long node, String propertyName )
    {
        return primitiveHasProperty( getNodeRecord( node ).forChangingData(), propertyName );
    }

    @Override
    public boolean relationshipHasProperty( long relationship, String propertyName )
    {
        return primitiveHasProperty(
                recordAccess.getRelRecords().getOrLoad( relationship, null ).forReadingData(), propertyName );
    }

    @Override
    public void setNodeProperty( long node, String propertyName, Object propertyValue )
    {
        RecordProxy<Long,NodeRecord,Void> nodeRecord = getNodeRecord( node );
        setPrimitiveProperty( nodeRecord, propertyName, propertyValue );

        flushStrategy.flush();
    }

    @Override
    public void setRelationshipProperty( long relationship, String propertyName, Object propertyValue )
    {
        RecordProxy<Long,RelationshipRecord,Void> relationshipRecord = getRelationshipRecord( relationship );
        setPrimitiveProperty( relationshipRecord, propertyName, propertyValue );

        flushStrategy.flush();
    }

    @Override
    public void removeNodeProperty( long node, String propertyName )
    {
        int propertyKey = getOrCreatePropertyKeyId( propertyName );
        propertyDeletor.removeProperty( getNodeRecord( node ), propertyKey, recordAccess.getPropertyRecords() );
        flushStrategy.flush();
    }

    @Override
    public void removeRelationshipProperty( long relationship,
                                            String propertyName )
    {
        int propertyKey = getOrCreatePropertyKeyId( propertyName );
        propertyDeletor.removeProperty( getRelationshipRecord( relationship ), propertyKey,
                recordAccess.getPropertyRecords() );
        flushStrategy.flush();
    }

    @Override
    public IndexCreator createDeferredSchemaIndex( Label label )
    {
        return new IndexCreatorImpl( actions, label );
    }

    private void setPrimitiveProperty( RecordProxy<Long,? extends PrimitiveRecord,Void> primitiveRecord,
            String propertyName, Object propertyValue )
    {
        int propertyKey = getOrCreatePropertyKeyId( propertyName );
        RecordAccess<Long,PropertyRecord,PrimitiveRecord> propertyRecords = recordAccess.getPropertyRecords();

        propertyCreator.primitiveSetProperty( primitiveRecord, propertyKey, propertyValue, propertyRecords );
    }

    private void validateIndexCanBeCreated( int labelId, int[] propertyKeyIds )
    {
        verifyIndexOrUniquenessConstraintCanBeCreated( labelId, propertyKeyIds,
                "Index for given {label;property} already exists" );
    }

    private void validateUniquenessConstraintCanBeCreated( int labelId, int[] propertyKeyIds )
    {
        verifyIndexOrUniquenessConstraintCanBeCreated( labelId, propertyKeyIds,
                "It is not allowed to create uniqueness constraints and indexes on the same {label;property}" );
    }

    private void verifyIndexOrUniquenessConstraintCanBeCreated( int labelId, int[] propertyKeyIds, String errorMessage )
    {
        LabelSchemaDescriptor schemaDescriptor = SchemaDescriptorFactory.forLabel( labelId, propertyKeyIds );
        ConstraintDescriptor constraintDescriptor = ConstraintDescriptorFactory.uniqueForLabel( labelId, propertyKeyIds );
        if ( schemaCache.hasIndexRule( schemaDescriptor ) || schemaCache.hasConstraintRule( constraintDescriptor ) )
        {
            throw new ConstraintViolationException( errorMessage );
        }
    }

    private void validateNodePropertyExistenceConstraintCanBeCreated( int labelId, int[] propertyKeyIds )
    {
        ConstraintDescriptor constraintDescriptor = ConstraintDescriptorFactory.existsForLabel( labelId, propertyKeyIds );

        if ( schemaCache.hasConstraintRule( constraintDescriptor ) )
        {
            throw new ConstraintViolationException(
                        "Node property existence constraint for given {label;property} already exists" );
        }
    }

    private void validateRelationshipConstraintCanBeCreated( int relTypeId, int propertyKeyId )
    {
        ConstraintDescriptor constraintDescriptor = ConstraintDescriptorFactory.existsForLabel( relTypeId, propertyKeyId );

        if ( schemaCache.hasConstraintRule( constraintDescriptor ) )
        {
            throw new ConstraintViolationException(
                        "Relationship property existence constraint for given {type;property} already exists" );
        }
    }

    private void createIndexRule( int labelId, int[] propertyKeyIds )
    {
        IndexRule schemaRule = IndexRule.indexRule(
                schemaStore.nextId(),
                NewIndexDescriptorFactory.forLabel( labelId, propertyKeyIds ),
                schemaIndexProviders.getDefaultProvider().getProviderDescriptor() );

        for ( DynamicRecord record : schemaStore.allocateFrom( schemaRule ) )
        {
            schemaStore.updateRecord( record );
        }
        schemaCache.addSchemaRule( schemaRule );
        labelsTouched = true;
        flushStrategy.forceFlush();
    }

    private void repopulateAllIndexes() throws IOException, IndexEntryConflictException
    {
        if ( !labelsTouched )
        {
            return;
        }

        final IndexRule[] rules = getIndexesNeedingPopulation();
        final Map<LabelSchemaDescriptor,IndexPopulator> populators = new HashMap<>();
        final Map<LabelSchemaDescriptor,NewIndexDescriptor> indexes = new HashMap<>();
        // the store is uncontended at this point, so creating a local LockService is safe.

        final LabelSchemaDescriptor[] descriptors = new LabelSchemaDescriptor[rules.length];

        for ( int i = 0; i < rules.length; i++ )
        {
            IndexRule rule = rules[i];
            NewIndexDescriptor index = rule.getIndexDescriptor();
            descriptors[i] = index.schema();
            IndexPopulator populator = schemaIndexProviders.apply( rule.getProviderDescriptor() )
                                                .getPopulator( rule.getId(), index, new IndexSamplingConfig( config ) );
            populator.create();
            populators.put( index.schema(), populator );
            indexes.put( index.schema(), index );
        }

        Visitor<NodeUpdates, IOException> propertyUpdateVisitor = updates -> {
            // Do a lookup from which property has changed to a list of indexes worried about that property.
            for ( IndexEntryUpdate indexUpdate :
                    updates.forIndexes( Iterables.asIterable( descriptors ), NO_UNCHANGED_PROPERTIES ) )
            {
                try
                {
                    populators.get( indexUpdate.descriptor() ).add( indexUpdate );
                }
                catch ( IndexEntryConflictException conflict )
                {
                    throw conflict.notAllowed( indexes.get( indexUpdate.descriptor() ) );
                }
            }
            return true;
        };

        List<LabelSchemaDescriptor> descriptorList = Arrays.asList( descriptors );
        int[] labelIds = descriptorList.stream()
                .mapToInt( LabelSchemaDescriptor::getLabelId )
                .toArray();

        int[] propertyKeyIds = descriptorList.stream()
                .flatMapToInt( d -> Arrays.stream( d.getPropertyIds() ) )
                .toArray();

        InitialNodeLabelCreationVisitor labelUpdateVisitor = new InitialNodeLabelCreationVisitor();
        StoreScan<IOException> storeScan = indexStoreView.visitNodes( labelIds,
                (propertyKeyId) -> PrimitiveIntCollections.contains( propertyKeyIds, propertyKeyId ),
                propertyUpdateVisitor, labelUpdateVisitor, true );
        storeScan.run();

        for ( IndexPopulator populator : populators.values() )
        {
            populator.verifyDeferredConstraints( indexStoreView );
            populator.close( true );
        }
        labelUpdateVisitor.close();
    }

    private void rebuildCounts()
    {
        CountsTracker counts = neoStores.getCounts();
        try
        {
            counts.start();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }

        CountsComputer.recomputeCounts( neoStores );
    }

    private class InitialNodeLabelCreationVisitor implements Visitor<NodeLabelUpdate, IOException>
    {
        LabelScanWriter writer = labelScanStore.newWriter();

        @Override
        public boolean visit( NodeLabelUpdate update ) throws IOException
        {
            writer.write( update );
            return true;
        }

        public void close() throws IOException
        {
            writer.close();
        }
    }

    private IndexRule[] getIndexesNeedingPopulation()
    {
        List<IndexRule> indexesNeedingPopulation = new ArrayList<>();
        for ( IndexRule rule : schemaCache.indexRules() )
        {
            SchemaIndexProvider provider = schemaIndexProviders.apply( rule.getProviderDescriptor() );
            if ( provider.getInitialState( rule.getId(), rule.getIndexDescriptor() ) != InternalIndexState.FAILED )
            {
                indexesNeedingPopulation.add( rule );
            }
        }
        return indexesNeedingPopulation.toArray( new IndexRule[indexesNeedingPopulation.size()] );
    }

    @Override
    public ConstraintCreator createDeferredConstraint( Label label )
    {
        return new BaseNodeConstraintCreator( new BatchSchemaActions(), label );
    }

    private void createUniquenessConstraintRule( LabelSchemaDescriptor schemaDescriptor )
    {
        // TODO: Do not create duplicate index

        long indexRuleId = schemaStore.nextId();
        long constraintRuleId = schemaStore.nextId();

        IndexRule indexRule =
                IndexRule.constraintIndexRule(
                    indexRuleId,
                    NewIndexDescriptorFactory.uniqueForSchema( schemaDescriptor ),
                    this.schemaIndexProviders.getDefaultProvider().getProviderDescriptor(),
                    constraintRuleId
                );
        ConstraintRule constraintRule =
                ConstraintRule.constraintRule(
                    constraintRuleId,
                    ConstraintDescriptorFactory.uniqueForSchema( schemaDescriptor ),
                    indexRuleId
                );

        for ( DynamicRecord record : schemaStore.allocateFrom( constraintRule ) )
        {
            schemaStore.updateRecord( record );
        }
        schemaCache.addSchemaRule( constraintRule );
        for ( DynamicRecord record : schemaStore.allocateFrom( indexRule ) )
        {
            schemaStore.updateRecord( record );
        }
        schemaCache.addSchemaRule( indexRule );
        labelsTouched = true;
        flushStrategy.forceFlush();
    }

    private void createNodePropertyExistenceConstraintRule( int labelId, int... propertyKeyIds )
    {
        SchemaRule rule = ConstraintRule.constraintRule( schemaStore.nextId(),
                ConstraintDescriptorFactory.existsForLabel( labelId, propertyKeyIds ) );

        for ( DynamicRecord record : schemaStore.allocateFrom( rule ) )
        {
            schemaStore.updateRecord( record );
        }
        schemaCache.addSchemaRule( rule );
        labelsTouched = true;
        flushStrategy.forceFlush();
    }

    private void createRelTypePropertyExistenceConstraintRule( int relTypeId, int... propertyKeyIds )
    {
        SchemaRule rule = ConstraintRule.constraintRule( schemaStore.nextId(),
                ConstraintDescriptorFactory.existsForRelType( relTypeId, propertyKeyIds ) );

        for ( DynamicRecord record : schemaStore.allocateFrom( rule ) )
        {
            schemaStore.updateRecord( record );
        }
        schemaCache.addSchemaRule( rule );
        flushStrategy.forceFlush();
    }

    private int getOrCreatePropertyKeyId( String name )
    {
        int propertyKeyId = tokenIdByName( propertyKeyTokens, name );
        if ( propertyKeyId == -1 )
        {
            propertyKeyId = createNewPropertyKeyId( name );
        }
        return propertyKeyId;
    }

    private int getOrCreateRelationshipTypeToken( RelationshipType type )
    {
        int typeId = tokenIdByName( relationshipTypeTokens, type.name() );
        if ( typeId == -1 )
        {
            typeId = createNewRelationshipType( type.name() );
        }
        return typeId;
    }

    private int getOrCreateLabelId( String name )
    {
        int labelId = tokenIdByName( labelTokens, name );
        if ( labelId == -1 )
        {
            labelId = createNewLabelId( name );
        }
        return labelId;
    }

    private int getOrCreateRelationshipTypeId( String name )
    {
        int relationshipTypeId = tokenIdByName( relationshipTypeTokens, name );
        if ( relationshipTypeId == -1 )
        {
            relationshipTypeId = createNewRelationshipType( name );
        }
        return relationshipTypeId;
    }

    private int tokenIdByName( BatchTokenHolder tokens, String name )
    {
        Token token = tokens.byName( name );
        return token != null ? token.id() : -1;
    }

    private boolean primitiveHasProperty( PrimitiveRecord record, String propertyName )
    {
        int propertyKeyId = tokenIdByName( propertyKeyTokens, propertyName );
        return propertyKeyId != -1 && propertyTraverser.findPropertyRecordContaining( record, propertyKeyId,
                recordAccess.getPropertyRecords(), false ) != Record.NO_NEXT_PROPERTY.intValue();
    }

    private void rejectAutoUpgrade( Map<String, String> params )
    {
        if ( parseBoolean( params.get( GraphDatabaseSettings.allow_store_upgrade.name() ) ) )
        {
            throw new IllegalArgumentException( "Batch inserter is not allowed to do upgrade of a store" +
                                                ", use " + EmbeddedGraphDatabase.class.getSimpleName() + " instead" );
        }
    }

    @Override
    public long createNode( Map<String, Object> properties, Label... labels )
    {
        return internalCreateNode( nodeStore.nextId(), properties, labels );
    }

    private long internalCreateNode( long nodeId, Map<String, Object> properties, Label... labels )
    {
        NodeRecord nodeRecord = recordAccess.getNodeRecords().create( nodeId, null ).forChangingData();
        nodeRecord.setInUse( true );
        nodeRecord.setCreated();
        nodeRecord.setNextProp( propertyCreator.createPropertyChain( nodeRecord,
                propertiesIterator( properties ), recordAccess.getPropertyRecords() ) );

        if ( labels.length > 0 )
        {
            setNodeLabels( nodeRecord, labels );
        }

        flushStrategy.flush();
        return nodeId;
    }

    private Iterator<PropertyBlock> propertiesIterator( Map<String, Object> properties )
    {
        if ( properties == null || properties.isEmpty() )
        {
            return Iterators.emptyIterator();
        }
        return new IteratorWrapper<PropertyBlock, Map.Entry<String,Object>>( properties.entrySet().iterator() )
        {
            @Override
            protected PropertyBlock underlyingObjectToObject( Entry<String, Object> property )
            {
                return propertyCreator.encodePropertyValue(
                        getOrCreatePropertyKeyId( property.getKey() ), property.getValue() );
            }
        };
    }

    private void setNodeLabels( NodeRecord nodeRecord, Label... labels )
    {
        NodeLabels nodeLabels = parseLabelsField( nodeRecord );
        nodeLabels.put( getOrCreateLabelIds( labels ), nodeStore, nodeStore.getDynamicLabelStore() );
        labelsTouched = true;
    }

    private long[] getOrCreateLabelIds( Label[] labels )
    {
        long[] ids = new long[labels.length];
        int cursor = 0;
        for ( int i = 0; i < ids.length; i++ )
        {
            int labelId = getOrCreateLabelId( labels[i].name() );
            if ( !arrayContains( ids, cursor, labelId ) )
            {
                ids[cursor++] = labelId;
            }
        }
        if ( cursor < ids.length )
        {
            ids = Arrays.copyOf( ids, cursor );
        }
        return ids;
    }

    private boolean arrayContains( long[] ids, int cursor, int labelId )
    {
        for ( int i = 0; i < cursor; i++ )
        {
            if ( ids[i] == labelId )
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public void createNode( long id, Map<String, Object> properties, Label... labels )
    {
        IdValidator.assertValidId( id, maxNodeId );
        if ( nodeStore.isInUse( id ) )
        {
            throw new IllegalArgumentException( "id=" + id + " already in use" );
        }
        long highId = nodeStore.getHighId();
        if ( highId <= id )
        {
            nodeStore.setHighestPossibleIdInUse( id );
        }
        internalCreateNode( id, properties, labels );
    }

    @Override
    public void setNodeLabels( long node, Label... labels )
    {
        NodeRecord record = getNodeRecord( node ).forChangingData();
        setNodeLabels( record, labels );
        flushStrategy.flush();
    }

    @Override
    public Iterable<Label> getNodeLabels( final long node )
    {
        return () -> {
            NodeRecord record = getNodeRecord( node ).forReadingData();
            long[] labels = parseLabelsField( record ).get( nodeStore );
            return map( labelIdToLabelFunction, PrimitiveLongCollections.iterator( labels ) );
        };
    }

    @Override
    public boolean nodeHasLabel( long node, Label label )
    {
        int labelId = tokenIdByName( labelTokens, label.name() );
        return labelId != -1 && nodeHasLabel( node, labelId );
    }

    private boolean nodeHasLabel( long node, int labelId )
    {
        NodeRecord record = getNodeRecord( node ).forReadingData();
        for ( long label : parseLabelsField( record ).get( nodeStore ) )
        {
            if ( label == labelId )
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public long createRelationship( long node1, long node2, RelationshipType type,
            Map<String, Object> properties )
    {
        long id = relationshipStore.nextId();
        int typeId = getOrCreateRelationshipTypeToken( type );
        relationshipCreator.relationshipCreate( id, typeId, node1, node2, recordAccess, noopLockClient );
        if ( properties != null && !properties.isEmpty() )
        {
            RelationshipRecord record = recordAccess.getRelRecords().getOrLoad( id, null ).forChangingData();
            record.setNextProp( propertyCreator.createPropertyChain( record,
                    propertiesIterator( properties ), recordAccess.getPropertyRecords() ) );
        }
        flushStrategy.flush();
        return id;
    }

    @Override
    public void setNodeProperties( long node, Map<String, Object> properties )
    {
        NodeRecord record = getNodeRecord( node ).forChangingData();
        if ( record.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
        {
            propertyDeletor.deletePropertyChain( record, recordAccess.getPropertyRecords() );
        }
        record.setNextProp( propertyCreator.createPropertyChain( record, propertiesIterator( properties ),
                recordAccess.getPropertyRecords() ) );
        flushStrategy.flush();
    }

    @Override
    public void setRelationshipProperties( long rel, Map<String, Object> properties )
    {
        RelationshipRecord record = recordAccess.getRelRecords().getOrLoad( rel, null ).forChangingData();
        if ( record.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
        {
            propertyDeletor.deletePropertyChain( record, recordAccess.getPropertyRecords() );
        }
        record.setNextProp( propertyCreator.createPropertyChain( record, propertiesIterator( properties ),
                recordAccess.getPropertyRecords() ) );
        flushStrategy.flush();
    }

    @Override
    public boolean nodeExists( long nodeId )
    {
        flushStrategy.forceFlush();
        return nodeStore.isInUse( nodeId );
    }

    @Override
    public Map<String, Object> getNodeProperties( long nodeId )
    {
        NodeRecord record = getNodeRecord( nodeId ).forReadingData();
        if ( record.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
        {
            return getPropertyChain( record.getNextProp() );
        }
        return Collections.emptyMap();
    }

    @Override
    public Iterable<Long> getRelationshipIds( long nodeId )
    {
        flushStrategy.forceFlush();
        return new BatchRelationshipIterable<Long>( neoStores, nodeId, cursors )
        {
            @Override
            protected Long nextFrom( long relId, int type, long startNode, long endNode )
            {
                return relId;
            }
        };
    }

    @Override
    public Iterable<BatchRelationship> getRelationships( long nodeId )
    {
        flushStrategy.forceFlush();
        return new BatchRelationshipIterable<BatchRelationship>( neoStores, nodeId, cursors )
        {
            @Override
            protected BatchRelationship nextFrom( long relId, int type, long startNode, long endNode )
            {
                return new BatchRelationship( relId, startNode, endNode,
                        (RelationshipType) relationshipTypeTokens.byId( type ) );
            }
        };
    }

    @Override
    public BatchRelationship getRelationshipById( long relId )
    {
        RelationshipRecord record = getRelationshipRecord( relId ).forReadingData();
        RelationshipType type = (RelationshipType) relationshipTypeTokens.byId( record.getType() );
        return new BatchRelationship( record.getId(), record.getFirstNode(), record.getSecondNode(), type );
    }

    @Override
    public Map<String, Object> getRelationshipProperties( long relId )
    {
        RelationshipRecord record = recordAccess.getRelRecords().getOrLoad( relId, null ).forChangingData();
        if ( record.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
        {
            return getPropertyChain( record.getNextProp() );
        }
        return Collections.emptyMap();
    }

    @Override
    public void shutdown()
    {
        if ( isShutdown )
        {
            throw new IllegalStateException( "Batch inserter already has shutdown" );
        }
        isShutdown = true;

        flushStrategy.forceFlush();

        rebuildCounts();

        try
        {
            repopulateAllIndexes();
        }
        catch ( IOException | IndexEntryConflictException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            cursors.close();
            neoStores.close();

            try
            {
                storeLocker.close();
            }
            catch ( IOException e )
            {
                throw new UnderlyingStorageException( "Could not release store lock", e );
            }

            msgLog.info( Thread.currentThread() + " Clean shutdown on BatchInserter(" + this + ")", true );
            life.shutdown();
        }
    }

    @Override
    public String toString()
    {
        return "EmbeddedBatchInserter[" + storeDir + "]";
    }

    private Map<String, Object> getPropertyChain( long nextProp )
    {
        final Map<String, Object> map = new HashMap<>();
        propertyTraverser.getPropertyChain( nextProp, recordAccess.getPropertyRecords(), new Listener<PropertyBlock>()
        {
            @Override
            public void receive( PropertyBlock propBlock )
            {
                String key = propertyKeyTokens.byId( propBlock.getKeyIndexId() ).name();
                DefinedProperty propertyData = propBlock.newPropertyData( propertyStore );
                Object value = propertyData.value() != null ? propertyData.value() :
                               propBlock.getType().getValue( propBlock, propertyStore );
                map.put( key, value );
            }
        } );
        return map;
    }

    private int createNewPropertyKeyId( String stringKey )
    {
        int keyId = (int) propertyKeyTokenStore.nextId();
        PropertyKeyTokenRecord record = new PropertyKeyTokenRecord( keyId );
        record.setInUse( true );
        record.setCreated();
        Collection<DynamicRecord> keyRecords =
                propertyKeyTokenStore.allocateNameRecords( encodeString( stringKey ) );
        record.setNameId( (int) Iterables.first( keyRecords ).getId() );
        record.addNameRecords( keyRecords );
        propertyKeyTokenStore.updateRecord( record );
        propertyKeyTokens.addToken( new Token( stringKey, keyId ) );
        return keyId;
    }

    private int createNewLabelId( String stringKey )
    {
        int keyId = (int) labelTokenStore.nextId();
        LabelTokenRecord record = new LabelTokenRecord( keyId );
        record.setInUse( true );
        record.setCreated();
        Collection<DynamicRecord> keyRecords =
                labelTokenStore.allocateNameRecords( encodeString( stringKey ) );
        record.setNameId( (int) Iterables.first( keyRecords ).getId() );
        record.addNameRecords( keyRecords );
        labelTokenStore.updateRecord( record );
        labelTokens.addToken( new Token( stringKey, keyId ) );
        return keyId;
    }

    private int createNewRelationshipType( String name )
    {
        int id = (int) relationshipTypeTokenStore.nextId();
        RelationshipTypeTokenRecord record = new RelationshipTypeTokenRecord( id );
        record.setInUse( true );
        record.setCreated();
        Collection<DynamicRecord> nameRecords = relationshipTypeTokenStore.allocateNameRecords( encodeString( name ) );
        record.setNameId( (int) Iterables.first( nameRecords ).getId() );
        record.addNameRecords( nameRecords );
        relationshipTypeTokenStore.updateRecord( record );
        relationshipTypeTokens.addToken( new RelationshipTypeToken( name, id ) );
        return id;
    }

    private RecordProxy<Long,NodeRecord,Void> getNodeRecord( long id )
    {
        if ( id < 0 || id >= nodeStore.getHighId() )
        {
            throw new NotFoundException( "id=" + id );
        }
        return recordAccess.getNodeRecords().getOrLoad( id, null );
    }

    private RecordProxy<Long,RelationshipRecord,Void> getRelationshipRecord( long id )
    {
        if ( id < 0 || id >= relationshipStore.getHighId() )
        {
            throw new NotFoundException( "id=" + id );
        }
        return recordAccess.getRelRecords().getOrLoad( id, null );
    }

    @Override
    public String getStoreDir()
    {
        return storeDir.getPath();
    }

    @Override
    public IndexConfigStore getIndexStore()
    {
        return this.indexStore;
    }

    public IdGeneratorFactory getIdGeneratorFactory()
    {
        return idGeneratorFactory;
    }

    private void dumpConfiguration( Map<String,String> config, PrintStream out )
    {
        for ( String key : config.keySet() )
        {
            Object value = config.get( key );
            if ( value != null )
            {
                out.println( key + "=" + value );
            }
        }
    }

    // test-access
    NeoStores getNeoStores()
    {
        return neoStores;
    }

    void forceFlushChanges()
    {
        flushStrategy.forceFlush();
    }

    private class BatchSchemaActions implements InternalSchemaActions
    {
        private int[] getOrCreatePropertyKeyIds( Iterable<String> properties )
        {
            return Iterables.stream( properties )
                    .mapToInt( BatchInserterImpl.this::getOrCreatePropertyKeyId )
                    .toArray();
        }

        private int[] getOrCreatePropertyKeyIds( String[] properties )
        {
            return Arrays.stream( properties )
                    .mapToInt( BatchInserterImpl.this::getOrCreatePropertyKeyId )
                    .toArray();
        }

        @Override
        public IndexDefinition createIndexDefinition( Label label, String... propertyKeys )
        {
            int labelId = getOrCreateLabelId( label.name() );
            int[] propertyKeyIds = getOrCreatePropertyKeyIds( propertyKeys );

            validateIndexCanBeCreated( labelId, propertyKeyIds );

            createIndexRule( labelId, propertyKeyIds );
            return new IndexDefinitionImpl( this, label, propertyKeys, false );
        }

        @Override
        public void dropIndexDefinitions( IndexDefinition indexDefinition )
        {
            throw unsupportedException();
        }

        @Override
        public ConstraintDefinition createPropertyUniquenessConstraint( IndexDefinition indexDefinition )
        {
            int labelId = getOrCreateLabelId( indexDefinition.getLabel().name() );
            int[] propertyKeyIds = getOrCreatePropertyKeyIds( indexDefinition.getPropertyKeys() );
            LabelSchemaDescriptor descriptor = SchemaDescriptorFactory.forLabel( labelId, propertyKeyIds );

            validateUniquenessConstraintCanBeCreated( labelId, propertyKeyIds );
            createUniquenessConstraintRule( descriptor );
            return new UniquenessConstraintDefinition( this, indexDefinition );
        }

        @Override
        public ConstraintDefinition createPropertyExistenceConstraint( Label label, String... propertyKeys )
        {
            int labelId = getOrCreateLabelId( label.name() );
            int[] propertyKeyIds = getOrCreatePropertyKeyIds( propertyKeys );

            validateNodePropertyExistenceConstraintCanBeCreated( labelId, propertyKeyIds );

            createNodePropertyExistenceConstraintRule( labelId, propertyKeyIds );
            return new NodePropertyExistenceConstraintDefinition( this, label, propertyKeys );
        }

        @Override
        public ConstraintDefinition createPropertyExistenceConstraint( RelationshipType type, String propertyKey )
                throws CreateConstraintFailureException, AlreadyConstrainedException
        {
            int relationshipTypeId = getOrCreateRelationshipTypeId( type.name() );
            int propertyKeyId = getOrCreatePropertyKeyId( propertyKey );

            validateRelationshipConstraintCanBeCreated( relationshipTypeId, propertyKeyId );

            createRelTypePropertyExistenceConstraintRule( relationshipTypeId, propertyKeyId );
            return new RelationshipPropertyExistenceConstraintDefinition( this, type, propertyKey );
        }

        @Override
        public void dropPropertyUniquenessConstraint( Label label, String[] properties )
        {
            throw unsupportedException();
        }

        @Override
        public void dropNodePropertyExistenceConstraint( Label label, String[] properties )
        {
            throw unsupportedException();
        }

        @Override
        public void dropRelationshipPropertyExistenceConstraint( RelationshipType type, String propertyKey )
        {
            throw unsupportedException();
        }

        @Override
        public String getUserMessage( KernelException e )
        {
            throw unsupportedException();
        }

        @Override
        public void assertInOpenTransaction()
        {
            // BatchInserterImpl always is expected to be running in one big single "transaction"
        }

        private UnsupportedOperationException unsupportedException()
        {
            return new UnsupportedOperationException( "Batch inserter doesn't support this" );
        }
    }

    interface FlushStrategy
    {
        void flush();

        void forceFlush();
    }

    static final class BatchedFlushStrategy implements FlushStrategy
    {
        private final DirectRecordAccessSet directRecordAccess;
        private final int batchSize;
        private int attempts;

        BatchedFlushStrategy( DirectRecordAccessSet directRecordAccess, int batchSize )
        {
            this.directRecordAccess = directRecordAccess;
            this.batchSize = batchSize;
        }

        @Override
        public void flush()
        {
            attempts++;
            if ( attempts >= batchSize)
            {
                forceFlush();
            }
        }

        @Override
        public void forceFlush()
        {
            directRecordAccess.commit();
            attempts = 0;
        }
    }
}
