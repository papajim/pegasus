/**
 * This file or a portion of this file is licensed under the terms of
 * the Globus Toolkit Public License, found at $PEGASUS_HOME/GTPL or
 * http://www.globus.org/toolkit/download/license.html.
 * This notice must appear in redistributions of this file
 * with or without modification.
 *
 * Redistributions of this Software, with or without modification, must reproduce
 * the GTPL in:
 * (1) the Software, or
 * (2) the Documentation or
 * some other similar material which is provided with the Software (if any).
 *
 * Copyright 1999-2004
 * University of Chicago and The University of Southern California.
 * All rights reserved.
 */

package org.griphyn.cPlanner.selector.site.heft;


import org.griphyn.cPlanner.classes.ADag;
import org.griphyn.cPlanner.classes.SubInfo;
import org.griphyn.cPlanner.classes.Profile;
import org.griphyn.cPlanner.classes.SiteInfo;
import org.griphyn.cPlanner.classes.JobManager;

import org.griphyn.cPlanner.common.PegasusProperties;
import org.griphyn.cPlanner.common.LogManager;

import org.griphyn.cPlanner.partitioner.graph.Graph;
import org.griphyn.cPlanner.partitioner.graph.GraphNode;
import org.griphyn.cPlanner.partitioner.graph.Adapter;
import org.griphyn.cPlanner.partitioner.graph.Bag;

import org.griphyn.cPlanner.selector.SiteSelector;

import org.griphyn.common.catalog.TransformationCatalogEntry;

import org.griphyn.common.catalog.transformation.Mapper;

import org.griphyn.cPlanner.poolinfo.PoolMode;
import org.griphyn.cPlanner.poolinfo.PoolInfoProvider;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.Comparator;
import java.util.Collections;

/**
 * The HEFT based site selector.
 *
 * @author Karan Vahi
 * @version $Revision$
 */
public class Algorithm {


    /**
     * The pegasus profile key that gives us the expected runtime.
     */
    public static final String RUNTIME_PROFILE_KEY = "runtime";

    /**
     * The average bandwidth between the sites. In mega bytes/per second.
     */
    public static final float AVERAGE_BANDWIDTH = 5;

    /**
     * The average data that is transferred in between 2 jobs in the workflow.
     * In megabytes.
     */
    public static final float AVERAGE_DATA_SIZE_BETWEEN_JOBS = 2;

    /**
     * The default number of nodes that are associated with a site if not found
     * in the site catalog.
     */
    public static final int DEFAULT_NUMBER_OF_FREE_NODES = 10;


    /**
     * The maximum finish time possible for a job.
     */
    public static final long MAXIMUM_FINISH_TIME = Long.MAX_VALUE;

    /**
     * The average communication cost between nodes.
     */
    private float mAverageCommunicationCost;

    /**
     * The workflow in the graph format, that needs to be scheduled.
     */
    private Graph mWorkflow;

    /**
     * Handle to the site catalog.
     */
    private PoolInfoProvider mSiteHandle;

    /**
     * The list of sites where the workflow can run.
     */
    private List mSites;

    /**
     * Map containing the number of free nodes for each site. The key is the site
     * name, and value is a <code>Site</code> object.
     */
    private Map mSiteMap;

    /**
     * Handle to the TCMapper.
     */
    protected Mapper mTCMapper;

    /**
     * The handle to the LogManager
     */
    private LogManager mLogger;

    /**
     * The default constructor.
     *
     * @param mapper  the tcmapper object.
     */
    public Algorithm( Mapper mapper ) {
        mTCMapper = mapper;
        mAverageCommunicationCost = (this.AVERAGE_BANDWIDTH / this.AVERAGE_DATA_SIZE_BETWEEN_JOBS);
        //load the SiteHandle
        PegasusProperties props = PegasusProperties.getInstance();
        mLogger = LogManager.getInstance();

        String poolClass = PoolMode.getImplementingClass( props.getPoolMode() );
        mSiteHandle = PoolMode.loadPoolInstance( poolClass,
                                                 props.getPoolFile(),
                                                 PoolMode.SINGLETON_LOAD);



        populateSiteMap( );
    }


    /**
     * Schedules the workflow using the heft.
     *
     * @param dag  the <code>ADag</code> object containing the abstract workflow
     *             that needs to be mapped.
     */
    public void schedule( ADag dag ){
        //convert the dag into a graph representation
        schedule( Adapter.convert( dag ) );
    }



    /**
     * Schedules the workflow according to the HEFT algorithm.
     *
     * @param workflow  the workflow that has to be scheduled.
     */
    public void schedule( Graph workflow ){
        mWorkflow = workflow;

        //compute weighted execution times for each job
        for( Iterator it = workflow.nodeIterator(); it.hasNext(); ){
            GraphNode node = ( GraphNode )it.next();
            SubInfo job    = (SubInfo)node.getContent();

            //add the heft bag to a node
            Float averageComputeTime = new Float( calculateAverageComputeTime( job ) );
            HeftBag b = new HeftBag();
            b.add( HeftBag.AVG_COMPUTE_TIME, averageComputeTime );
            node.setBag( b );

            mLogger.log( "Average Compute Time " + node.getID() + " is " + averageComputeTime,
                         LogManager.DEBUG_MESSAGE_LEVEL );

        }

        //add a dummy root
        Bag bag;
        GraphNode dummyRoot = new GraphNode( "dummy", "dummy" );
        workflow.addRoot( dummyRoot );
        bag = new HeftBag();
        //downward rank for the root is set to 0
        bag.add( HeftBag.DOWNWARD_RANK, new Float( 0 ) );
        dummyRoot.setBag( bag );

        //do a breadth first traversal and compute the downward ranks
        Iterator it = workflow.iterator();
        dummyRoot = ( GraphNode )it.next(); //we have the dummy root
        Float drank;
        //the dummy root has a downward rank of 0
        dummyRoot.getBag().add( HeftBag.DOWNWARD_RANK, new Float( 0 ) );
        //stores the nodes in sorted ascending order
        List sortedNodes = new LinkedList();
        while ( it.hasNext() ){
            GraphNode node = ( GraphNode ) it.next();
            drank = new Float( computeDownwardRank( node ) );
            bag = node.getBag();
            bag.add( HeftBag.DOWNWARD_RANK , drank );
            sortedNodes.add( node );
            mLogger.log( "Downward rank for node " + node.getID() + " is " + drank,
                         LogManager.DEBUG_MESSAGE_LEVEL );
        }

        //sort the node
        Collections.sort( sortedNodes, new HeftGraphNodeComparator() );


        //the start time and end time for the dummy root is 0
        dummyRoot.getBag().add( HeftBag.ACTUAL_START_TIME, new Long( 0 ) );
        dummyRoot.getBag().add( HeftBag.ACTUAL_FINISH_TIME, new Long( 0 ) );

        //schedule out the sorted order of the nodes
        for( it = sortedNodes.iterator(); it.hasNext(); ){
            GraphNode current = (GraphNode) it.next();
            bag           = current.getBag();
            mLogger.log("Scheduling node " + current.getID(),
                        LogManager.DEBUG_MESSAGE_LEVEL);

            //figure out the sites where a job can run
            SubInfo job = (SubInfo) current.getContent();
            List runnableSites = mTCMapper.getSiteList( job.getTXNamespace(),
                                                        job.getTXName(),
                                                        job.getTXVersion(),
                                                        mSites);

            //for each runnable site get the estimated finish time
            //and schedule job on site that minimizes the finish time
            String site;
            long est_result[ ];
            long result[] = new long[ 2 ];
            result [ 1 ] = this.MAXIMUM_FINISH_TIME;
            for( Iterator rit = runnableSites.iterator(); rit.hasNext(); ){
                site = (String) rit.next();
                est_result = calculateEstimatedStartAndFinishTime( current, site );

                //if existing EFT is greater than the returned EFT
                //set existing EFT to the returned EFT
                if( result[ 1 ] > est_result[ 1 ] ){
                    result[ 0 ] = est_result[ 0 ];
                    result[ 1 ] = est_result[ 1 ];
                    //tentatively schedule the job for that site
                    bag.add( HeftBag.SCHEDULED_SITE , site );
                }
            }

            //update the site selected with the job
            bag.add( HeftBag.ACTUAL_START_TIME, new Long( result[ 0 ] ));
            bag.add( HeftBag.ACTUAL_FINISH_TIME, new Long( result[ 1 ] ) );
            site = (String)bag.get( HeftBag.SCHEDULED_SITE );
            scheduleJob( site,
                         result[ 0 ],
                         result[ 1 ]  );

            //log the information
            StringBuffer sb = new StringBuffer();
            sb.append( "Scheduled job " ).append( current.getID() ).
               append( " to site " ).append( site ).
               append( " with from  ").append( result[ 0 ] ).
               append( " till " ).append( result[ 1 ] );

            mLogger.log( sb.toString(), LogManager.DEBUG_MESSAGE_LEVEL );
        }//end of going through all the sorted nodes

        //remove the dummy root
        mWorkflow.remove( dummyRoot.getID() );
    }


    /**
     * Returns the makespan of the scheduled workflow. It is maximum of the
     * actual finish times for the leaves of the scheduled workflow.
     *
     * @return long  the makespan of the workflow.
     */
    public long getMakespan( ){
        long result = -1;

        //compute the maximum of the actual end times of leaves
        for( Iterator it = mWorkflow.getLeaves().iterator(); it.hasNext() ; ){
            GraphNode node = ( GraphNode )it.next();
            Long endTime   = ( Long ) node.getBag().get( HeftBag.ACTUAL_FINISH_TIME );
            //sanity check
            if( endTime == null ){
                throw new RuntimeException( "Looks like the leave node is unscheduled " +  node.getID());
            }
            if( endTime > result ){
                result = endTime;
            }
        }

        return result;
    }


    /**
     * Estimates the start and finish time of a job on a site.
     *
     * @param node   the node that is being scheduled
     * @param site  the site for which the finish time is reqd.
     *
     * @return  long[0] the estimated start time.
     *          long[1] the estimated finish time.
     */
    protected long[] calculateEstimatedStartAndFinishTime( GraphNode node, String site ){

        SubInfo job = ( SubInfo )node.getContent();
        long[] result = new long[2];

        //calculate the ready time for the job
        //that is time by which all the data needed
        //by the job has reached the site.
        long readyTime = 0;
        for( Iterator it = node.getParents().iterator(); it.hasNext(); ){
            GraphNode parent = ( GraphNode )it.next();
            long current = 0;
            //add the parent finish time to current
            current += (Long)parent.getBag().get( HeftBag.ACTUAL_FINISH_TIME );

            //if the parent was scheduled on another site
            //add the average data transfer time.
            if( !parent.getBag().get( HeftBag.SCHEDULED_SITE ).equals( site ) ){
                current += this.mAverageCommunicationCost;
            }

            if ( current > readyTime ){
                //ready time is maximum of all currents
                readyTime = current;
            }
        }

        //the estimated start time is the maximum
        //of the ready time and available time of the site
        //using non insertion based policy for time being
        result[ 0 ] = getAvailableTime( site , readyTime );

// do not need it, as available time is always >= ready time
//        if ( result[ 0 ] < readyTime ){
//            result[ 0 ] = readyTime;
//       }


        //the estimated finish time is est + compute time on site
        List entries = mTCMapper.getTCList( job.getTXNamespace(),
                                            job.getTXName(),
                                            job.getTXVersion(),
                                            site );
        //pick the first one for time being
        TransformationCatalogEntry entry = ( TransformationCatalogEntry ) entries.get( 0 );
        result[ 1 ] = result[ 0 ] + getExpectedRuntime( entry );

        //est now stores the estimated finish time
        return result;
    }

    /**
     * Computes the downward rank of a node.
     *
     * The downward rank of node i is
     *                                   _    ___
     *           max {       rank( n ) + w  + c    }
     *         j E pred( i )     d  j     j    ji
     *
     *
     *
     * @param node   the <code>GraphNode</code> whose rank needs to be computed.
     *
     * @return computed rank.
     */
    protected float computeDownwardRank( GraphNode node ){
        float result = 0;
        float value = 0;

        for( Iterator it = node.getParents().iterator(); it.hasNext(); ){
            GraphNode p = (GraphNode)it.next();
            Bag pbag    = p.getBag();

            value += ( getFloatValue ( pbag.get( HeftBag.DOWNWARD_RANK ) )+
                       getFloatValue ( pbag.get( HeftBag.AVG_COMPUTE_TIME ) ) +
                       mAverageCommunicationCost
                     );

            if( value > result ){
                 result = value;
            }
        }

        return result;
    }

    /**
     * Returns the average compute time in seconds for a job.
     *
     * @param job the job whose average compute time is to be computed.
     *
     * @return the weighted compute time in seconds.
     */
    protected float calculateAverageComputeTime( SubInfo job ){
        //get all the TC entries for the sites where a job can run
        List runnableSites = mTCMapper.getSiteList( job.getTXNamespace(),
                                                    job.getTXName(),
                                                    job.getTXVersion(),
                                                    mSites );

        //sanity check
        if( runnableSites.isEmpty() ){
            throw new RuntimeException( "No runnable site for job " + job.getName() );
        }

        //for each runnable site get the expected runtime
        String site;
        int total_nodes = 0;
        int total = 0;
        for( Iterator it = runnableSites.iterator(); it.hasNext(); ){
            site = ( String ) it.next();
            int nodes = getFreeNodesForSite( site );
            List entries = mTCMapper.getTCList( job.getTXNamespace(),
                                                job.getTXName(),
                                                job.getTXVersion(),
                                                site );

            //pick the first one for time being
            TransformationCatalogEntry entry = ( TransformationCatalogEntry ) entries.get( 0 );
            int jobRuntime = getExpectedRuntime( entry );
            total_nodes += nodes;
            total += jobRuntime * nodes;

        }

        return total/total_nodes;
    }


    /**
     * Return expected runtime.
     *
     * @param entry  the <code>TransformationCatalogEntry</code> object.
     *
     * @return the runtime in seconds.
     */
    protected int getExpectedRuntime( TransformationCatalogEntry entry ){
        List profiles = entry.getProfiles( Profile.VDS );
        int result = -1;

        if( profiles != null ){
            for (Iterator it = profiles.iterator(); it.hasNext(); ) {
                Profile p = (Profile) it.next();
                if (p.getProfileKey().equals(this.RUNTIME_PROFILE_KEY)) {
                    result = Integer.parseInt(p.getProfileValue());
                    break;
                }
            }
        }
        //sanity check for time being
        if( result < 1 ){
            throw new RuntimeException( "Invalid or no runtime specified" );
        }

        return result;
    }


    /**
     * Populates the number of free nodes for each site, by querying the
     * Site Catalog.
     */
    protected void populateSiteMap(){
        mSiteMap = new HashMap();

        //for testing purposes
        mSites = new ArrayList();
        mSites.add( "isi_viz" );
        mSites.add( "isi_skynet" );

        String value = null;
        int nodes = 0;
        for( Iterator it = mSites.iterator(); it.hasNext(); ){
            String site = (String)it.next();
            SiteInfo s = mSiteHandle.getPoolEntry( site, "vanilla" );
            JobManager manager = s.selectJobManager( "vanilla", true );
            value = (String)manager.getInfo( JobManager.IDLE_NODES );

            try {
                nodes = ( value == null )?
                        this.DEFAULT_NUMBER_OF_FREE_NODES:
                        new Integer( value ).intValue();

            }catch( Exception e ){
                nodes = this.DEFAULT_NUMBER_OF_FREE_NODES;
            }

            mSiteMap.put( site, new Site( site,  nodes ) );
        }

    }


    /**
     * Returns the freenodes for a site.
     *
     * @param site   the site identifier.
     *
     * @return number of nodes
     */
    protected int getFreeNodesForSite( String site ){
        if( mSiteMap.containsKey( site ) ){
            return ( ( Site )mSiteMap.get( site )).getAvailableProcessors();
        }
        else{
            throw new RuntimeException( "The number of free nodes not available for site " + site );
        }
    }


    /**
     * Schedules a job to a site.
     *
     * @param site  the site at which to schedule
     * @param start the start time for job
     * @param end   the end time of job
     */
    protected void scheduleJob( String site, long start , long end ){
        Site s = ( Site )mSiteMap.get( site );
        s.scheduleJob( start, end );

    }

    /**
     * Returns the available time for a site.
     *
     * @param site       the site at which you want to schedule the job.
     * @param readyTime  the time at which all the data reqd by the job will arrive at site.
     *
     * @return the available time of the site.
     */
    protected long getAvailableTime( String site , long readyTime ){
        if( mSiteMap.containsKey( site ) ){
            return ( ( Site )mSiteMap.get( site )).getAvailableTime( readyTime );
        }
        else{
            throw new RuntimeException( "Site information unavailable for site " + site );
        }


    }

    /**
     * This method returns a String describing the site selection technique
     * that is being implemented by the implementing class.
     *
     * @return String
     */
    public String description() {
        return "Heft based Site Selector";
    }

    /**
     * The call out to the site selector to determine on what pool the job
     * should be scheduled.
     *
     * @param job SubInfo the <code>SubInfo</code> object corresponding to
     *   the job whose execution pool we want to determine.
     * @param pools the list of <code>String</code> objects representing the
     *   execution pools that can be used.
     * @return if the pool is found to which the job can be mapped, a string
     *   of the form <code>executionpool:jobmanager</code> where the
     *   jobmanager can be null. If the pool is not found, then set
     *   poolhandle to NONE. null - if some error occured .
     */
    public String mapJob2ExecPool(SubInfo job, List pools) {
        return "";
    }

    /**
     * A convenience method to get the intValue for the object passed.
     *
     * @param key   the key to be converted
     *
     * @return the floatt value if object an integer, else -1
     */
    private float getFloatValue( Object key ){

        float k = -1;
        //try{
            k = ( (Float) key).floatValue();
        //}
        //catch( Exception e ){}

        return k;

    }
}

/**
 * Comparator for GraphNode objects that allow us to sort on basis of
 * the downward rank computed.
 */
class HeftGraphNodeComparator implements Comparator{

/**
   * Implementation of the {@link java.lang.Comparable} interface.
   * Compares this object with the specified object for order. Returns a
   * negative integer, zero, or a positive integer as this object is
   * less than, equal to, or greater than the specified object. The
   * definitions are compared by their type, and by their short ids.
   *
   * @param o1 is the object to be compared
   * @param o2 is the object to be compared with o1.
   *
   * @return a negative number, zero, or a positive number, if the
   * object compared against is less than, equals or greater than
   * this object.
   * @exception ClassCastException if the specified object's type
   * prevents it from being compared to this Object.
   */
  public int compare( Object o1, Object o2 )
  {
    if ( o1 instanceof GraphNode && o2 instanceof GraphNode ) {
      GraphNode g1 = ( GraphNode ) o1;
      GraphNode g2 = ( GraphNode ) o2;

      float drank1 = (( Float )g1.getBag().get( HeftBag.DOWNWARD_RANK ));//.floatValue();
      float drank2 = (( Float )g2.getBag().get( HeftBag.DOWNWARD_RANK ));//.floatValue();

      return (int)(drank1 - drank2);
    } else {
      throw new ClassCastException( "object is not a GraphNode" );
    }
  }

}
