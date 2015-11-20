package com.linkedin.datastream.server;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamDestination;
import com.linkedin.datastream.common.DatastreamSource;
import com.linkedin.datastream.common.VerifiableProperties;


/**
 * Class that uses the Transport provider to manage the topics used by the datastream
 */
public class DestinationManager {
  private static final Logger LOG = LoggerFactory.getLogger(DestinationManager.class.getName());

  private final TransportProvider _transportProvider;
  private final int DEFAULT_NUMBER_PARTITIONS = 1;

  public DestinationManager(TransportProvider transportProvider) {
    _transportProvider = transportProvider;
  }

  /**
   * populates the datastream destination for the newly created datastreams.
   * Caller (Datastream leader) should pass in all the datastreams present in the system.
   * This method will take care of de-duping the datastreams, i.e. if there is an existing
   * datastream with the same source, they will use the same destination.
   * @param datastreams All datastreams in the current system.
   */
  public void populateDatastreamDestination(List<Datastream> datastreams) {
    Objects.requireNonNull(datastreams, "Datastream should not be null");

    HashMap<DatastreamSource, DatastreamDestination> sourceDestinationMapping = new HashMap<>();
    datastreams.stream().filter(d ->  d.hasDestination() && !d.getDestination().getConnectionString().isEmpty())
        .forEach(d -> sourceDestinationMapping.put(d.getSource(), d.getDestination()));

    LOG.debug("Datastream Source -> Destination mapping before populating new datastream destinations",
        sourceDestinationMapping);

    for (Datastream datastream : datastreams) {
      if ( datastream.hasDestination() && !datastream.getDestination().getConnectionString().isEmpty()) {
        continue;
      }

      // De-dup the datastreams, Set the destination for the duplicate datastreams same as the existing ones.
      if (sourceDestinationMapping.containsKey(datastream.getSource())) {
        DatastreamDestination destination = sourceDestinationMapping.get(datastream.getSource());
        LOG.info(String.format("Datastream %s has same source as existing datastream, Setting the destination %s",
            datastream.getName(), destination));
        datastream.setDestination(destination);
      } else {
        String connectionString = createTopic(datastream);
        LOG.info(String
            .format("Datastream %s has an unique source, Creating a new destination topic %s", datastream.getName(),
                connectionString));
        datastream.setDestination(new DatastreamDestination());
        datastream.getDestination().setConnectionString(connectionString);
        sourceDestinationMapping.put(datastream.getSource(), datastream.getDestination());
      }
    }

    LOG.debug("Datastream Source -> Destination mapping after the populating new datastream destinations",
        sourceDestinationMapping);
  }

  private String createTopic(Datastream datastream) {
    Properties datastreamProperties = new Properties();
    datastreamProperties.putAll(datastream.getMetadata());
    Properties topicProperties = new VerifiableProperties(datastreamProperties).getDomainProperties("topic");
    return _transportProvider.createTopic(getTopicName(datastream), DEFAULT_NUMBER_PARTITIONS, topicProperties);
  }

  private String getTopicName(Datastream datastream) {
    URI sourceUri = URI.create(datastream.getSource().getConnectionString());
    return String.format("%s_%s", sourceUri.getPath().replace("/", "_"), UUID.randomUUID());
  }

  /**
   * Delete the datastream destination for a particular datastream.
   * Caller should pass in all the datastreams present in the system.
   * This method will ensure that there are no other references to the destination before deleting it.
   * @param datastream Datastream whose destination needs to be deleted.
   * @param allDatastreams All the datastreams in the system.
   */
  public void deleteDatastreamDestination(Datastream datastream, List<Datastream> allDatastreams) {
    Objects.requireNonNull(datastream, "Datastream should not be null");
    Objects.requireNonNull(datastream.getDestination(), "Datastream destination should not be null");
    Objects.requireNonNull(allDatastreams, "allDatastreams should not be null");
    Stream<Datastream> duplicateDatastreams = allDatastreams.stream().filter(d ->
        d.getDestination().equals(datastream.getDestination()) && !d.getName().equalsIgnoreCase(datastream.getName()));

    // If there are no datastreams using the same destination, then delete the topic.
    if(duplicateDatastreams.count() == 0) {
      _transportProvider.dropTopic(datastream.getDestination().getConnectionString());
    } else {
      LOG.info(String.format("There are existing datastreams %s with the same destination (%s) as datastream %s ",
          duplicateDatastreams, datastream.getDestination(), datastream.getName()));
    }
  }
}