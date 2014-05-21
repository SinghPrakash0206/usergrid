/*
 * Copyright 2014 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.usergrid.corepersistence;

import com.yammer.metrics.annotation.Metered;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.usergrid.persistence.ConnectedEntityRef;
import org.apache.usergrid.persistence.ConnectionRef;
import org.apache.usergrid.persistence.Entity;
import org.apache.usergrid.persistence.EntityFactory;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.persistence.EntityRef;
import org.apache.usergrid.persistence.IndexBucketLocator;
import org.apache.usergrid.persistence.index.query.Query;
import org.apache.usergrid.persistence.RelationManager;
import org.apache.usergrid.persistence.Results;
import org.apache.usergrid.persistence.Schema;
import static org.apache.usergrid.persistence.Schema.PROPERTY_CREATED;
import static org.apache.usergrid.persistence.Schema.TYPE_APPLICATION;
import static org.apache.usergrid.persistence.Schema.TYPE_ENTITY;
import static org.apache.usergrid.persistence.Schema.defaultCollectionName;
import static org.apache.usergrid.persistence.Schema.getDefaultSchema;
import org.apache.usergrid.persistence.SimpleEntityRef;
import org.apache.usergrid.persistence.cassandra.ConnectionRefImpl;
import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.impl.CollectionScopeImpl;
import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.entities.User;
import org.apache.usergrid.persistence.graph.Edge;
import org.apache.usergrid.persistence.graph.GraphManager;
import org.apache.usergrid.persistence.graph.impl.SimpleMarkedEdge;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchByEdgeType;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchEdgeType;
import org.apache.usergrid.persistence.index.EntityIndex;
import org.apache.usergrid.persistence.index.IndexScope;
import org.apache.usergrid.persistence.index.query.CandidateResult;
import org.apache.usergrid.persistence.index.impl.IndexScopeImpl;
import org.apache.usergrid.persistence.index.query.CandidateResults;
import org.apache.usergrid.persistence.index.query.Identifier;
import org.apache.usergrid.persistence.index.query.Query.Level;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;
import org.apache.usergrid.persistence.model.util.UUIDGenerator;
import org.apache.usergrid.persistence.index.query.tree.Operand;
import org.apache.usergrid.persistence.schema.CollectionInfo;
import static org.apache.usergrid.utils.InflectionUtils.singularize;
import org.apache.usergrid.utils.MapUtils;
import static org.apache.usergrid.utils.MapUtils.addMapSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import rx.Observable;


/**
 * Implement good-old Usergrid RelationManager with the new-fangled Core Persistence API.
 */
public class CpRelationManager implements RelationManager {

    private static final Logger logger = LoggerFactory.getLogger( CpRelationManager.class );

    public static String COLLECTION_SUFFIX = "zzzcollectionzzz";
    public static String ALL_TYPES = "zzzalltypesnzzz";

    private CpEntityManagerFactory emf;
    
    private CpManagerCache managerCache;

    private EntityManager em;

    private UUID applicationId;

    private EntityRef headEntity;

    private org.apache.usergrid.persistence.model.entity.Entity cpHeadEntity;

    private ApplicationScope applicationScope;
    private CollectionScope headEntityScope;



    public CpRelationManager() {}


    public CpRelationManager init( 
        EntityManager em, CpEntityManagerFactory emf, UUID applicationId,
        EntityRef headEntity, IndexBucketLocator indexBucketLocator ) {

        Assert.notNull( em, "Entity manager cannot be null" );
        Assert.notNull( applicationId, "Application Id cannot be null" );
        Assert.notNull( headEntity, "Head entity cannot be null" );
        Assert.notNull( headEntity.getUuid(), "Head entity uuid cannot be null" );

        this.em = em;
        this.applicationId = applicationId;
        this.headEntity = headEntity;
        this.managerCache = emf.getManagerCache();

        this.applicationScope = emf.getApplicationScope(applicationId);
        this.headEntityScope = new CollectionScopeImpl( 
            this.applicationScope.getApplication(), 
            this.applicationScope.getApplication(), 
            Schema.defaultCollectionName( headEntity.getType()) );

        EntityCollectionManager ecm = managerCache.getEntityCollectionManager(headEntityScope);
        this.cpHeadEntity = ecm.load( new SimpleId( 
            headEntity.getUuid(), headEntity.getType() )).toBlockingObservable().last();

        Assert.notNull( cpHeadEntity, "cpHeadEntity cannot be null" );

        return this;
    }

    
    @Override
    public Set<String> getCollectionIndexes(String collectionName) throws Exception {
        final Set<String> indexes = new HashSet<String>();

        GraphManager gm = managerCache.getGraphManager(applicationScope);

        Observable<String> types= gm.getEdgeTypesFromSource( 
            new SimpleSearchEdgeType( cpHeadEntity.getId(), null ));

        Iterator<String> iter = types.toBlockingObservable().getIterator();
        while ( iter.hasNext() ) {
            indexes.add( iter.next() );
        }
        return indexes;
    }


    @Override
    public Map<String, Map<UUID, Set<String>>> getOwners() throws Exception {

        Map<EntityRef, Set<String>> containerEntities = getContainingCollections();

        Map<String, Map<UUID, Set<String>>> owners = new LinkedHashMap<String, Map<UUID, Set<String>>>();

        for ( EntityRef owner : containerEntities.keySet() ) {
            Set<String> collections = containerEntities.get( owner );
            for ( String collection : collections ) {
                MapUtils.addMapMapSet( owners, owner.getType(), owner.getUuid(), collection );
            }
        }

        return owners;
    }


    private Map<EntityRef, Set<String>> getContainingCollections() {

        Map<EntityRef, Set<String>> results = new LinkedHashMap<EntityRef, Set<String>>();

        GraphManager gm = managerCache.getGraphManager(applicationScope);

        Iterator<String> edgeTypes = gm.getEdgeTypesToTarget( new SimpleSearchEdgeType( 
            cpHeadEntity.getId(), null) ).toBlockingObservable().getIterator();

        while ( edgeTypes.hasNext() ) {

            String edgeType = edgeTypes.next();

            Observable<Edge> edges = gm.loadEdgesToTarget( new SimpleSearchByEdgeType( 
                cpHeadEntity.getId(), edgeType, cpHeadEntity.getVersion(), null ));

            Iterator<Edge> iter = edges.toBlockingObservable().getIterator();
            while ( iter.hasNext() ) {
                Edge edge = iter.next();

                if ( !edge.getType().endsWith(COLLECTION_SUFFIX) ) {
                    continue;
                }

                String collName = edge.getType().substring(0, edge.getType().indexOf(COLLECTION_SUFFIX));

                CollectionScope collScope = new CollectionScopeImpl( 
                    this.applicationScope.getApplication(), 
                    this.applicationScope.getApplication(), 
                    Schema.defaultCollectionName( edge.getSourceNode().getType() ));

                EntityCollectionManager ecm = managerCache.getEntityCollectionManager(collScope);

                org.apache.usergrid.persistence.model.entity.Entity container = 
                    ecm.load( edge.getSourceNode() ).toBlockingObservable().last();

                EntityRef eref = new SimpleEntityRef( 
                    container.getId().getType(), container.getId().getUuid() );

                String cEdgeType = edge.getType();
                if ( cEdgeType.endsWith( COLLECTION_SUFFIX )) {
                    cEdgeType = cEdgeType.substring( 0, cEdgeType.indexOf(COLLECTION_SUFFIX));
                }
                addMapSet( results, eref, cEdgeType );
            }
        }

        EntityRef applicationRef = new SimpleEntityRef( TYPE_APPLICATION, applicationId );
        if ( !results.containsKey( applicationRef ) ) {
            addMapSet( results, applicationRef, defaultCollectionName( headEntity.getType() ) );
        }
        return results;
    }


    @SuppressWarnings("unchecked")
    @Metered(group = "core", name = "RelationManager_isOwner")
    @Override
    public boolean isCollectionMember(String collName, EntityRef entity) throws Exception {

        // TODO: review & determine if this implementation is correct

        Id entityId = new SimpleId( entity.getUuid(), entity.getType() );

        GraphManager gm = managerCache.getGraphManager(applicationScope);
        Observable<Edge> edges = gm.loadEdgesToTarget( new SimpleSearchByEdgeType( 
            entityId, collName, cpHeadEntity.getVersion(), null ));

        // TODO: more efficient way to do this?
        Iterator<Edge> iter = edges.toBlockingObservable().getIterator();
        while ( iter.hasNext() ) {
            Edge edge = iter.next();
            if ( edge.getSourceNode().equals( cpHeadEntity.getId() )) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isConnectionMember(String connectionName, EntityRef entity) throws Exception {

        // TODO: review / determine if this implementation is correct, it does not look right

        return isCollectionMember(connectionName, entity);
    }

    @Override
    public Set<String> getCollections() throws Exception {

        Map<String, CollectionInfo> collections = getDefaultSchema().getCollections( headEntity.getType() );
        if ( collections == null ) {
            return null;
        }

        return collections.keySet();
    }

    @Override
    public Results getCollection(String collectionName, UUID startResult, int count, 
            Level resultsLevel, boolean reversed) throws Exception {

        // TODO: how to set Query startResult?

        Query query = Query.fromQL("select *");
        query.setLimit(count);
        query.setReversed(reversed);

        return searchCollection(collectionName, query);
    }

    @Override
    public Results getCollection(
            String collName, Query query, Level level) throws Exception {

        return searchCollection(collName, query);
    }


    // add to a named collection of the head entity
    @Override
    public Entity addToCollection(String collName, EntityRef memberRef) throws Exception {

        // load the new member entity to be added to the collection from its default scope
        CollectionScope memberScope = new CollectionScopeImpl( 
            applicationScope.getApplication(), 
            applicationScope.getApplication(), 
            Schema.defaultCollectionName( memberRef.getType()));
        EntityCollectionManager memberMgr = managerCache.getEntityCollectionManager(memberScope);

        org.apache.usergrid.persistence.model.entity.Entity memberEntity = memberMgr.load(
            new SimpleId( memberRef.getUuid(), memberRef.getType() )).toBlockingObservable().last();

        if ( memberEntity == null ) {
            throw new RuntimeException("Unable to load entity uuid=" 
                    + memberRef.getUuid() + " type=" + memberRef.getType());
        }

        // create graph edge connection from head entity to member entity
        Edge edge = new SimpleMarkedEdge( cpHeadEntity.getId(), collName + COLLECTION_SUFFIX, 
            memberEntity.getId(), UUIDGenerator.newTimeUUID(), false );

        GraphManager gm = managerCache.getGraphManager(applicationScope);
        gm.writeEdge(edge).toBlockingObservable().last();

        // index member into entity connection | type scope
        IndexScope collectionIndexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            collName + COLLECTION_SUFFIX);
        EntityIndex collectionIndex = managerCache.getEntityIndex(collectionIndexScope);
        collectionIndex.index( memberEntity );

        // index member into entity connection | all-types scope
        IndexScope allCollectionsIndexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            ALL_TYPES);
        EntityIndex allCollectionIndex = managerCache.getEntityIndex(allCollectionsIndexScope);
        allCollectionIndex.index( memberEntity );

        logger.debug("Added entity {}:{} to collection {}", 
            new String[] { memberRef.getUuid().toString(), memberRef.getType(), collName }); 

        logger.debug("With head entity scope is {}:{}:{}",
            new String[] { 
                headEntityScope.getApplication().toString(), 
                headEntityScope.getOwner().toString(),
                headEntityScope.getName()}); 

        return em.get( memberRef );
    }


    @Override
    public Entity addToCollections(List<EntityRef> owners, String collName) throws Exception {

        // TODO: this addToCollections() implementation seems wrong.
        for ( EntityRef eref : owners ) {
            addToCollection( collName, eref ); 
        }

        return null;
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createItemInCollection")
    public Entity createItemInCollection(
        String collName, String itemType, Map<String, Object> properties) throws Exception {

        if ( headEntity.getUuid().equals( applicationId ) ) {
            if ( itemType.equals( TYPE_ENTITY ) ) {
                itemType = singularize( collName );
            }

// TODO: complete when Role support is ready 
//
//            if ( itemType.equals( TYPE_ROLE ) ) {
//                Long inactivity = ( Long ) properties.get( PROPERTY_INACTIVITY );
//                if ( inactivity == null ) {
//                    inactivity = 0L;
//                }
//                return em.createRole( ( String ) properties.get( PROPERTY_NAME ),
//                        ( String ) properties.get( PROPERTY_TITLE ), inactivity );
//            }
            return em.create( itemType, properties );
        }

// TODO: complete when Role support is ready 
//
//        else if ( headEntity.getType().equals( Group.ENTITY_TYPE ) 
//                && ( collectionName.equals( COLLECTION_ROLES ) ) ) {
//            UUID groupId = headEntity.getUuid();
//            String roleName = ( String ) properties.get( PROPERTY_NAME );
//            return em.createGroupRole( groupId, roleName, ( Long ) properties.get( PROPERTY_INACTIVITY ) );
//        }

        CollectionInfo collection = getDefaultSchema().getCollection(headEntity.getType(),collName);
        if ( ( collection != null ) && !collection.getType().equals( itemType ) ) {
            return null;
        }

        properties = getDefaultSchema().cleanUpdatedProperties( itemType, properties, true );

        Entity itemEntity = em.create( itemType, properties );

        if ( itemEntity != null ) {

            addToCollection( collName, itemEntity );

            if ( collection.getLinkedCollection() != null ) {
                getRelationManager( itemEntity )
                    .addToCollection( collection.getLinkedCollection(), getHeadEntity());
            }
        }

        return itemEntity;  
    }

    @Override
    public void removeFromCollection(String collName, EntityRef memberRef) throws Exception {

//        if ( !collectionEntityExists(collName) ) {
//            return;
//        }
//
//        // look up collection entity in system collections scope 
//        org.apache.usergrid.persistence.model.entity.Entity collectionEntity = 
//            getCollectionEntity(collName);

        // load the entity to be added to the collection
        CollectionScope memberScope = new CollectionScopeImpl( 
            this.applicationScope.getApplication(), 
            this.applicationScope.getApplication(), 
            Schema.defaultCollectionName( memberRef.getType() ));
        EntityCollectionManager memberMgr = managerCache.getEntityCollectionManager(memberScope);

        org.apache.usergrid.persistence.model.entity.Entity memberEntity = memberMgr.load(
            new SimpleId( memberRef.getUuid(), memberRef.getType() )).toBlockingObservable().last();

        IndexScope indexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            collName + COLLECTION_SUFFIX);

        EntityIndex ei = managerCache.getEntityIndex(indexScope);
        ei.deindex( memberEntity );

        Edge edge = new SimpleMarkedEdge( cpHeadEntity.getId(), collName + COLLECTION_SUFFIX, 
            memberEntity.getId(), UUIDGenerator.newTimeUUID(), false );
        GraphManager gm = managerCache.getGraphManager(applicationScope);
        gm.deleteEdge(edge).toBlockingObservable().last();
    }


    @Override
    public void copyRelationships(String srcRelationName, EntityRef dstEntityRef, 
            String dstRelationName) throws Exception {

        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results searchCollection(String collName, Query query) throws Exception {

        if ( query == null ) {
            query = new Query();
        }

        headEntity = em.validate( headEntity );

        CollectionInfo collection = 
            getDefaultSchema().getCollection( headEntity.getType(), collName );

        query.setEntityType( collection.getType() );

        //org.apache.usergrid.persistence.index.query.Query cpQuery = createCpQuery( query );

        IndexScope indexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            collName + COLLECTION_SUFFIX);
        EntityIndex ei = managerCache.getEntityIndex(indexScope);
      
        logger.debug("Searching collection {}", collName);
        logger.debug("Searching head entity scope {}:{}:{}",
            new String[] { 
                headEntityScope.getApplication().toString(), 
                headEntityScope.getOwner().toString(),
                collName }); 

        CandidateResults crs = ei.search( addIdentifiers(query) );

        return buildResults( query, crs, collName );
    }


//    private org.apache.usergrid.persistence.index.query.Query createCpQuery( Query query ) {
//
//        org.apache.usergrid.persistence.index.query.Query cpQuery =
//           new org.apache.usergrid.persistence.index.query.Query();
//
//        cpQuery.setCollection( query.getCollection() );
//        cpQuery.setConnectionType( query.getConnectionType() );
//        cpQuery.setCursor( query.getCursor() );
//        cpQuery.setEntityType( query.getEntityType() );
//        cpQuery.setFinishTime( query.getFinishTime() );
//        cpQuery.setLimit( query.getLimit() );
//        cpQuery.setPad( query.isPad() );
//        cpQuery.setPermissions( query.getPermissions() );
//        cpQuery.setQl( query.getQl() );
//        cpQuery.setReversed( query.isReversed() );
//        cpQuery.setStartTime( query.getStartTime() );
//
//        for ( Query.SortPredicate sp : query.getSortPredicates() ) {
//
//            org.apache.usergrid.persistence.index.query.Query.SortDirection newdir = 
//                org.apache.usergrid.persistence.index.query.Query.SortDirection.ASCENDING;
//
//            if ( sp.getDirection().equals( Query.SortDirection.DESCENDING )) {
//                newdir =org.apache.usergrid.persistence.index.query.Query.SortDirection.DESCENDING;
//            }
//
//            org.apache.usergrid.persistence.index.query.Query.SortPredicate newsp = 
//                new org.apache.usergrid.persistence.index.query.Query.SortPredicate( 
//                    sp.getPropertyName(), newdir );
//
//            cpQuery.addSort( newsp );
//        }
//
//        if ( cpQuery.isReversed() ) {
//
//            org.apache.usergrid.persistence.index.query.Query.SortPredicate newsp = 
//                new org.apache.usergrid.persistence.index.query.Query.SortPredicate( 
//                    PROPERTY_CREATED, 
//                        org.apache.usergrid.persistence.index.query.Query.SortDirection.DESCENDING );
//
//            cpQuery.addSort( newsp ); 
//        }
//
//        if ( cpQuery.getSortPredicates().isEmpty() ) {
//
//            org.apache.usergrid.persistence.index.query.Query.SortPredicate newsp = 
//                new org.apache.usergrid.persistence.index.query.Query.SortPredicate( 
//                    PROPERTY_CREATED, 
//                        org.apache.usergrid.persistence.index.query.Query.SortDirection.ASCENDING);
//
//            cpQuery.addSort( newsp ); 
//        }
//
//        List<org.apache.usergrid.persistence.index.query.tree.Operand> 
//            filterClauses = query.getFilterClauses();
//
//        for ( Operand oldop : filterClauses ) {
//            
//            if ( oldop instanceof org.apache.usergrid.persistence.index.query.tree.ContainsOperand ) {
//
//                org.apache.usergrid.persistence.index.query.tree.ContainsOperand casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.ContainsOperand)oldop;
//                cpQuery.addContainsFilter( 
//                    casted.getProperty().getText(), casted.getLiteral().getValue().toString());
//
//            } else if ( oldop instanceof org.apache.usergrid.persistence.index.query.tree.Equal ) {
//
//                org.apache.usergrid.persistence.index.query.tree.Equal casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.Equal)oldop;
//                cpQuery.addEqualityFilter(
//                        casted.getProperty().getText(), casted.getLiteral().getValue() );
//
//            } else if ( oldop instanceof org.apache.usergrid.persistence.index.query.tree.GreaterThan ) {
//
//                org.apache.usergrid.persistence.index.query.tree.GreaterThan casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.GreaterThan)oldop;
//                cpQuery.addGreaterThanFilter(
//                        casted.getProperty().getText(), casted.getLiteral().getValue());
//
//            } else if(oldop instanceof org.apache.usergrid.persistence.index.query.tree.GreaterThanEqual){
//
//                org.apache.usergrid.persistence.index.query.tree.GreaterThanEqual casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.GreaterThanEqual)oldop;
//                cpQuery.addGreaterThanEqualFilter(
//                        casted.getProperty().getText(), casted.getLiteral().getValue());
//
//            } else if ( oldop instanceof org.apache.usergrid.persistence.index.query.tree.LessThan ) {
//
//                org.apache.usergrid.persistence.index.query.tree.LessThan casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.LessThan)oldop;
//                cpQuery.addLessThanFilter(
//                        casted.getProperty().getText(), casted.getLiteral().getValue());
//
//            } else if ( oldop instanceof org.apache.usergrid.persistence.index.query.tree.LessThanEqual) {
//
//                org.apache.usergrid.persistence.index.query.tree.LessThanEqual casted = 
//                    (org.apache.usergrid.persistence.index.query.tree.LessThanEqual)oldop;
//                cpQuery.addLessThanEqualFilter(
//                        casted.getProperty().getText(), casted.getLiteral().getValue());
//            }
//        }
//
//        if ( cpQuery.getRootOperand() == null ) {
//
//            // a name alias or email alias was specified
//            if ( query.containsSingleNameOrEmailIdentifier() ) {
//
//                Identifier ident = query.getSingleIdentifier();
//
//                // an email was specified.  An edge case that only applies to users.  
//                // This is fulgy to put here, but required.
//                if ( query.getEntityType().equals( User.ENTITY_TYPE ) && ident.isEmail() ) {
//
//                    org.apache.usergrid.persistence.index.query.Query newQuery = 
//                        org.apache.usergrid.persistence.index.query.Query.fromQL(
//                            "select * where email='" + query.getSingleNameOrEmailIdentifier()+ "'");
//
//                    cpQuery.setRootOperand( newQuery.getRootOperand() );
//                }
//
//                // use the ident with the default alias. could be an email
//                else {
//
//                    org.apache.usergrid.persistence.index.query.Query newQuery = 
//                        org.apache.usergrid.persistence.index.query.Query.fromQL(
//                            "select * where name='" + query.getSingleNameOrEmailIdentifier()+ "'");
//
//                    cpQuery.setRootOperand( newQuery.getRootOperand() );
//                }
//
//            } else if ( query.containsSingleUuidIdentifier() ) {
//
//                org.apache.usergrid.persistence.index.query.Query newQuery = 
//                    org.apache.usergrid.persistence.index.query.Query.fromQL(
//                        "select * where uuid='" + query.getSingleUuidIdentifier() + "'");
//
//                cpQuery.setRootOperand( newQuery.getRootOperand() );
//            }
//
//        }
//
//        return cpQuery;
//    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connection_ref")
    public ConnectionRef createConnection( ConnectionRef connection ) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connectionType")
    public ConnectionRef createConnection( 
            String connectionType, EntityRef connectedEntityRef ) throws Exception {

        headEntity = em.validate( headEntity );
        connectedEntityRef = em.validate( connectedEntityRef );

        ConnectionRefImpl connection = new ConnectionRefImpl( 
            headEntity, connectionType, connectedEntityRef );

        CollectionScope targetScope = new CollectionScopeImpl( 
            applicationScope.getApplication(), 
            applicationScope.getApplication(), 
            Schema.defaultCollectionName( connectedEntityRef.getType() ));

        EntityCollectionManager targetEcm = managerCache.getEntityCollectionManager(targetScope);
        org.apache.usergrid.persistence.model.entity.Entity targetEntity = targetEcm.load(
            new SimpleId( connectedEntityRef.getUuid(), connectedEntityRef.getType() ))
                .toBlockingObservable().last();

        // create graph edge connection from head entity to member entity
        Edge edge = new SimpleMarkedEdge( cpHeadEntity.getId(), connectionType, 
            targetEntity.getId(), UUIDGenerator.newTimeUUID(), false );

        GraphManager gm = managerCache.getGraphManager(applicationScope);
        gm.writeEdge(edge).toBlockingObservable().last();

        // Index the new connection in app|source|type context
        IndexScope indexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            connectionType);
        EntityIndex ei = managerCache.getEntityIndex(indexScope);
        ei.index( targetEntity );

        // Index the new connection in app|source|type context
        IndexScope allTypesIndexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            ALL_TYPES);
        EntityIndex aei = managerCache.getEntityIndex(allTypesIndexScope);
        aei.index( targetEntity );

        return connection;
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_paired_connection_type")
    public ConnectionRef createConnection( 
            String pairedConnectionType, EntityRef pairedEntity, String connectionType,
            EntityRef connectedEntityRef ) throws Exception {
        
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connected_entity_ref")
    public ConnectionRef createConnection( ConnectedEntityRef... connections ) throws Exception {

        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public ConnectionRef connectionRef(
            String connectionType, 
            EntityRef connectedEntityRef) throws Exception {

        ConnectionRef connection = new ConnectionRefImpl( 
                headEntity, connectionType, connectedEntityRef );

        return connection;
    }

    @Override
    public ConnectionRef connectionRef(String pairedConnectionType, EntityRef pairedEntity, 
            String connectionType, EntityRef connectedEntityRef) throws Exception {
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public ConnectionRef connectionRef(ConnectedEntityRef... connections) {
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public void deleteConnection(ConnectionRef connectionRef) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes(UUID connectedEntityId) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes() throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes(boolean filterConnection) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectedEntities(String connectionType, String connectedEntityType, 
            Level resultsLevel) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectingEntities(String connectionType, String connectedEntityType, 
            Level resultsLevel) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectingEntities(String connectionType, String entityType, 
            Level level, int count) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results searchConnectedEntities( Query query ) throws Exception {

        if ( query == null ) {
            query = new Query();
        }

        headEntity = em.validate( headEntity );

//        org.apache.usergrid.persistence.index.query.Query cpQuery = createCpQuery( query );

        IndexScope indexScope = new IndexScopeImpl(
            applicationScope.getApplication(), 
            cpHeadEntity.getId(), 
            ALL_TYPES);

        EntityIndex ei = managerCache.getEntityIndex(indexScope);
      
        logger.debug("Searching connections from all-targets scope {}:{}:{}",
            new String[] { 
                indexScope.getApplication().toString(), 
                indexScope.getOwner().toString(),
                indexScope.getName()}); 

        // get list of all connection types from this entity
        List<String> connectionTypes = new ArrayList<String>();

        if ( query.getConnectionType() != null ) {

            // query specifies type
            connectionTypes.add( query.getConnectionType() );

        } else {

            // find all outgoing connection types of entity
            GraphManager gm = managerCache.getGraphManager(applicationScope);

            Observable<String> types= gm.getEdgeTypesFromSource( 
                new SimpleSearchEdgeType( cpHeadEntity.getId(), null ));

            Iterator<String> iter = types.toBlockingObservable().getIterator();
            while ( iter.hasNext() ) {
                connectionTypes.add( iter.next() );
            }
        }

        CandidateResults crs = ei.search( addIdentifiers(query) );

        return buildResults( query , crs, query.getConnectionType() );
    }


    private Query addIdentifiers( Query query ) {

        if ( query.getRootOperand() == null ) {

            // a name alias or email alias was specified
            if ( query.containsSingleNameOrEmailIdentifier() ) {

                Identifier ident = query.getSingleIdentifier();

                // an email was specified.  An edge case that only applies to users.  
                // This is fulgy to put here, but required.
                if ( query.getEntityType().equals( User.ENTITY_TYPE ) && ident.isEmail() ) {

                    org.apache.usergrid.persistence.index.query.Query newQuery = 
                        org.apache.usergrid.persistence.index.query.Query.fromQL(
                            "select * where email='" + query.getSingleNameOrEmailIdentifier()+ "'");

                    query.setRootOperand( newQuery.getRootOperand() );
                }

                // use the ident with the default alias. could be an email
                else {

                    org.apache.usergrid.persistence.index.query.Query newQuery = 
                        org.apache.usergrid.persistence.index.query.Query.fromQL(
                            "select * where name='" + query.getSingleNameOrEmailIdentifier()+ "'");

                    query.setRootOperand( newQuery.getRootOperand() );
                }

            } else if ( query.containsSingleUuidIdentifier() ) {

                org.apache.usergrid.persistence.index.query.Query newQuery = 
                    org.apache.usergrid.persistence.index.query.Query.fromQL(
                        "select * where uuid='" + query.getSingleUuidIdentifier() + "'");

                query.setRootOperand( newQuery.getRootOperand() );
            }
        }

        return query;
    }


    @Override
    public Set<String> getConnectionIndexes(String connectionType) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    
    private CpRelationManager getRelationManager( EntityRef headEntity ) {
        CpRelationManager rmi = new CpRelationManager();
        rmi.init( em, emf, applicationId, headEntity, null);
        return rmi;
    }


    /** side effect: converts headEntity into an Entity if it is an EntityRef! */
    private Entity getHeadEntity() throws Exception {
        Entity entity = null;
        if ( headEntity instanceof Entity ) {
            entity = ( Entity ) headEntity;
        }
        else {
            entity = em.get( headEntity );
            headEntity = entity;
        }
        return entity;
    }


    private Results buildResults(Query query, CandidateResults crs, String collName ) {

        Results results = null;

        if ( query.getLevel().equals( Level.IDS )) {
            
            // TODO: replace this with List<Id> someday
            List<UUID> ids = new ArrayList<UUID>();
            Iterator<CandidateResult> iter = crs.iterator();
            while ( iter.hasNext() ) {
                ids.add( iter.next().getId().getUuid() );
            }
            results = Results.fromIdList( ids );

        } else if ( query.getLevel().equals( Level.REFS )) {

            List<EntityRef> entityRefs = new ArrayList<EntityRef>();
            Iterator<CandidateResult> iter = crs.iterator();
            while ( iter.hasNext() ) {
                Id id = iter.next().getId();
                entityRefs.add( new SimpleEntityRef( id.getType(), id.getUuid() ));
            } 
            results = Results.fromRefList(entityRefs);

        } else {

            CollectionScope collScope = new CollectionScopeImpl( 
                applicationScope.getApplication(), 
                applicationScope.getApplication(), 
                Schema.defaultCollectionName( query.getEntityType() ) );

            EntityCollectionManager ecm = managerCache.getEntityCollectionManager(collScope);

            // first, build map of latest versions of entities
            Map<Id, org.apache.usergrid.persistence.model.entity.Entity> latestVersions = 
                new LinkedHashMap<Id, org.apache.usergrid.persistence.model.entity.Entity>();

            Iterator<CandidateResult> iter = crs.iterator();
            while ( iter.hasNext() ) {

                CandidateResult cr = iter.next();

                org.apache.usergrid.persistence.model.entity.Entity e =
                    ecm.load( cr.getId() ).toBlockingObservable().last();

                if ( cr.getVersion().compareTo(e.getVersion()) < 0 )  {
                    logger.debug("Stale version uuid:{} type:{} v:{}", 
                        new Object[] {cr.getId().getUuid(), cr.getId().getType(), cr.getVersion()} );
                    continue;
                }

                org.apache.usergrid.persistence.model.entity.Entity alreadySeen = 
                    latestVersions.get( e.getId() );

                if ( alreadySeen == null ) { // never seen it, so add to map
                    latestVersions.put( e.getId(), e);

                } else {
                    // we seen this id before, only add entity if newer version
                    if ( e.getVersion().compareTo( alreadySeen.getVersion() ) > 0 ) {
                        latestVersions.put( e.getId(), e);
                    }
                }
            }

            // now build collection of old-school entities
            List<Entity> entities = new ArrayList<Entity>();
            for ( Id id : latestVersions.keySet() ) {

                org.apache.usergrid.persistence.model.entity.Entity e =
                    latestVersions.get( id );

                Entity entity = EntityFactory.newEntity(
                    e.getId().getUuid(), e.getField("type").getValue().toString() );

                Map<String, Object> entityMap = CpEntityMapUtils.toMap( e );
                entity.addProperties( entityMap ); 
                entities.add( entity );
            }

            results = Results.fromEntities( entities );
        }

        results.setCursor( crs.getCursor() );
        results.setQueryProcessor( new CpQueryProcessor(em, query, headEntity, collName) );

        return results;
    }



}
