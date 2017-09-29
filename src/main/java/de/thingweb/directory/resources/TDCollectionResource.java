package de.thingweb.directory.resources;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ResponseHeader;



import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


import java.util.Set;

import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.system.Txn;
import org.apache.jena.vocabulary.RDF;



import de.thingweb.directory.ThingDirectory;
import de.thingweb.directory.VocabularyUtils;
import de.thingweb.directory.rest.BadRequestException;
import de.thingweb.directory.rest.CollectionFilter;
import de.thingweb.directory.rest.CollectionFilterFactory;
import de.thingweb.directory.rest.CollectionResource;
import de.thingweb.directory.rest.RESTException;
import de.thingweb.directory.rest.RESTResource;
import de.thingweb.directory.sparql.client.Connector;
import de.thingweb.directory.sparql.client.Queries;
import de.thingweb.directory.vocabulary.TD;

@Api(value = "thing_description")
public class TDCollectionResource extends CollectionResource {
	
	public static final String PARAMETER_QUERY = "query";
	
	public static final String PARAMETER_TEXT_SEARCH = "text";
	
	private static class SPARQLFilter implements CollectionFilter {
		
		private final Set<String> names;
		
		public SPARQLFilter(String q) {
			Element pattern = QueryFactory.createElement("{" + q + "}");
			Query query = Queries.filterTDs(pattern);
			
			try (RDFConnection conn = Connector.getConnection()) {
				names = Txn.calculateWrite(conn, () -> {
					Set<String> tds = new HashSet<>();
					conn.querySelect(query, (qs) -> {
						String uri = qs.getResource("id").getURI();
						if (uri.contains("td/")) {
							String id = uri.substring(uri.lastIndexOf("td/") + 3);
							tds.add(id);
						}
					});
					return tds;
				});
			}
		}
		
		@Override
		public boolean keep(RESTResource child) {
			return names.contains(child.getName());
		}
		
		public Set<String> getNames() {
			return names;
		}
		
	}
	
	private static class FreeTextFilter implements CollectionFilter {
		
		private final String keywords;

		public FreeTextFilter(String kw) {
			keywords = kw;
		}
		
		@Override
		public boolean keep(RESTResource child) {
			return true; // TODO
		}
		
	}

	public TDCollectionResource() {
		super("/td", TDResource.factory(), new CollectionFilterFactory() {
			@Override
			public CollectionFilter create(Map<String, String> parameters) {
				if (parameters.containsKey(PARAMETER_QUERY)) {
					String q = parameters.get(PARAMETER_QUERY);
					return new SPARQLFilter(q);
				} else if (parameters.containsKey(PARAMETER_TEXT_SEARCH)) {
					String keywords = parameters.get(PARAMETER_TEXT_SEARCH);
					return new FreeTextFilter(keywords);
				} else {
					return new CollectionResource.KeepAllFilter();
				}
			}
		});
		
		SPARQLFilter filter = new SPARQLFilter("?s ?p ?o");
		for (String name : filter.getNames()) {
			repost(name);
		}
	}

	@ApiOperation(value = "Lists all TDs in the repository.",
	              httpMethod = "GET",
	              produces = "application/json")
	@ApiImplicitParams({
		@ApiImplicitParam(name = "query",
	                      value = "SPARQL graph pattern (URI-encoded)"),
		@ApiImplicitParam(name = "text",
		                  value = "Keyword for boolean text search query")
	})
	@Override
	public void get(Map<String, String> parameters, OutputStream out) throws RESTException {
		super.get(parameters, out);

		// TODO include TDs?
	}
	
	@ApiOperation(value = "Creates (adds) a TD to the repository.",
	              httpMethod = "POST",
	              consumes = "application/ld+json, application/rdf+xml, text/turtle, application/n-triples",
	              responseHeaders = @ResponseHeader(name = "Location",
	                                                description = "Relative URI to the created resource"))
	@Override
	public RESTResource post(Map<String, String> parameters, InputStream payload) throws RESTException {
		Model graph = RDFDocument.read(parameters, payload);
		Model schema = VocabularyUtils.mergeVocabularies();
		// FIXME reasoning on the union dataset!
		InfModel inf = ModelFactory.createInfModel(ReasonerRegistry.getOWLMicroReasoner(), schema, graph);

		List<RESTResource> resources = new ArrayList<>();
		
		if (parameters.containsKey(RESTResource.PARAMETER_CONTENT_TYPE)) {
			// forces default RDF format
			parameters.remove(RESTResource.PARAMETER_CONTENT_TYPE);
		}
		
		ResIterator it = inf.listResourcesWithProperty(RDF.type, TD.Thing);
		while (it.hasNext()) {
			Resource root = it.next();

			// duplicate detection
			// TODO isomorphic TDs too
			boolean duplicate = false;
			if (root.isURIResource()) {
				String query = "ASK WHERE { GRAPH <%s> { ?s ?p ?o } }";
				RDFConnection conn = Connector.getConnection();
				duplicate = conn.queryAsk(String.format(query, root.getURI()));
			}

			if (!duplicate) {
				// TODO keyword extraction
				
				Model td = extractTD(root);
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				td.write(out, RDFDocument.DEFAULT_FORMAT);
				ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
				
				RESTResource res = super.post(parameters, in);
				resources.add(res);
			} else {
				ThingDirectory.LOG.info("Registering invalid TD, no instance of td:Thing: " + graph);
			}
		}
		
		if (resources.isEmpty()) {
			ThingDirectory.LOG.info("Registering invalid TD, no instance of td:Thing: " + graph);
			throw new BadRequestException();
		} else {
			return resources.get(0);
		}
	}
	
	private static Model extractTD(Resource root) {
		Model td = ModelFactory.createDefaultModel();
		  
		StmtIterator it = root.listProperties();
		while (it.hasNext()) {
			Statement st = it.next();
			td.add(st);
			if (!st.getPredicate().equals(RDF.type) && st.getObject().isResource()) {
				Resource node = st.getObject().asResource();
				if (!node.hasProperty(RDF.type, TD.Thing)) {
					// FIXME cycle detection (if interaction patterns reference each other)
					td.add(extractTD(node));
				}
			}
		}
		  
		return td;
	}

}
