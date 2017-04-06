/* (c) 2014 - 2016 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.rest.catalog;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CatalogFacade;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.config.util.XStreamPersister;
import org.geoserver.rest.ResourceNotFoundException;
import org.geoserver.rest.RestBaseController;
import org.geoserver.rest.RestException;
import org.geoserver.rest.converters.XStreamMessageConverter;
import org.geoserver.rest.wrapper.RestWrapper;
import org.geotools.util.logging.Logging;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Coverage store controller
 */
@RestController
@ControllerAdvice
@RequestMapping(path = RestBaseController.ROOT_PATH, produces = {
        MediaType.APPLICATION_JSON_VALUE,
        MediaType.APPLICATION_XML_VALUE,
        MediaType.TEXT_HTML_VALUE})
public class LayerGroupController extends CatalogController {
    private static final Logger LOGGER = Logging.getLogger(LayerGroupController.class);

    @Autowired
    public LayerGroupController(@Qualifier("catalog") Catalog catalog) {
        super(catalog);
    }

    @GetMapping(value = {"/layergroups", "/workspaces/{workspace}/layergroups"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public RestWrapper getLayerGroups(@PathVariable( name = "workspace", required = false) String workspaceName) {
        if(workspaceName != null && catalog.getWorkspaceByName(workspaceName) == null) {
            throw new ResourceNotFoundException("Workspace " + workspaceName + " not found");
        }
        List<LayerGroupInfo> layerGroupInfos = workspaceName != null ?
                catalog.getLayerGroupsByWorkspace(workspaceName) : catalog.getLayerGroupsByWorkspace(CatalogFacade.NO_WORKSPACE);
        return wrapList(layerGroupInfos, LayerGroupInfo.class);
    }

    @GetMapping(value = {"/layergroups/{layerGroup}", "/workspaces/{workspace}/layergroups/{layerGroup}"},
            produces = {MediaType.APPLICATION_JSON_VALUE, CatalogController.TEXT_JSON, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_HTML_VALUE})
    public RestWrapper getLayerGroup(@PathVariable (name = "layerGroup") String layerGroupName,
                                     @PathVariable (name = "workspace", required = false) String workspaceName,
                                     @RequestParam (name = "quietOnNotFound", required = false) Boolean quietOnNotFound) {
        if(workspaceName != null && catalog.getWorkspaceByName(workspaceName) == null) {
            throw new ResourceNotFoundException("Workspace " + workspaceName + " not found");
        }

        LayerGroupInfo layerGroupInfo = workspaceName != null ?
            catalog.getLayerGroupByName(workspaceName, layerGroupName) : catalog.getLayerGroupByName(layerGroupName);

        String errorMessage = "No such layer group " + layerGroupName +
                (workspaceName == null ? "" : " in workspace " + workspaceName);
        return wrapObject(layerGroupInfo, LayerGroupInfo.class, errorMessage, quietOnNotFound);
    }

    @PostMapping(value = {"/layergroups", "/workspaces/{workspace}/layergroups"},
            consumes = {MediaType.APPLICATION_JSON_VALUE, CatalogController.TEXT_JSON,
                    MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> postLayerGroup(@RequestBody LayerGroupInfo lg,
            @PathVariable( name = "workspace", required = false) String workspaceName,
            UriComponentsBuilder builder) throws Exception{
        if(workspaceName != null && catalog.getWorkspaceByName(workspaceName) == null) {
            throw new ResourceNotFoundException("Workspace " + workspaceName + " not found");
        }
        checkFullAdminRequired(workspaceName);
        
        if ( lg.getLayers().isEmpty() ) {
            throw new  RestException( "layer group must not be empty", HttpStatus.BAD_REQUEST );
        }

        if ( lg.getBounds() == null ) {
            LOGGER.fine( "Auto calculating layer group bounds");
            new CatalogBuilder( catalog ).calculateLayerGroupBounds(lg);
        }

        if (workspaceName != null) {
            lg.setWorkspace(catalog.getWorkspaceByName(workspaceName));
        }

        if (lg.getMode() == null) {
            LOGGER.fine("Setting layer group mode SINGLE");
            lg.setMode(LayerGroupInfo.Mode.SINGLE);
        }

        catalog.validate(lg, true).throwIfInvalid();
        catalog.add(lg);

        String layerGroupName = lg.getName();
        LOGGER.info("POST layer group " + layerGroupName);
        UriComponents uriComponents = builder.path("/layergroups/{layerGroupName}")
                .buildAndExpand(layerGroupName);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setLocation(uriComponents.toUri());

        return new ResponseEntity<String>(layerGroupName, httpHeaders, HttpStatus.CREATED);
    }

    @PutMapping( value = {"/layergroups/{layerGroup}", "/workspaces/{workspace}/layergroups/{layerGroup}"},
            consumes = {MediaType.APPLICATION_JSON_VALUE, CatalogController.TEXT_JSON,
                    MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public void putLayerGroup(@RequestBody LayerGroupInfo lg,
            @PathVariable( name = "workspace", required = false) String workspaceName,
            @PathVariable( name = "layerGroup" ) String layerGroupName) throws Exception {
        if(workspaceName != null && catalog.getWorkspaceByName(workspaceName) == null) {
            throw new ResourceNotFoundException("Workspace " + workspaceName + " not found");
        }
        checkFullAdminRequired(workspaceName);

        
        LOGGER.info( "PUT layer group " + layerGroupName
                + (workspaceName == null ? ", workspace " + workspaceName : ""));
        LayerGroupInfo original = workspaceName != null ?
                catalog.getLayerGroupByName(workspaceName, layerGroupName) : catalog.getLayerGroupByName(layerGroupName);

        //ensure not a name change
        if ( lg.getName() != null && !lg.getName().equals( original.getName() ) ) {
            throw new RestException( "Can't change name of a layer group", HttpStatus.FORBIDDEN );
        }

        //ensure not a workspace change
        if (lg.getWorkspace() != null) {
            if (!lg.getWorkspace().equals(original.getWorkspace())) {
                throw new RestException( "Can't change the workspace of a layer group, instead " +
                        "DELETE from existing workspace and POST to new workspace", HttpStatus.FORBIDDEN );
            }
        }

        new CatalogBuilder( catalog ).updateLayerGroup( original, lg );
        catalog.save( original );
    }

    @DeleteMapping( value = {"/layergroups/{layerGroup}", "/workspaces/{workspace}/layergroups/{layerGroup}"})
    public void deleteLayerGroup(@PathVariable( name = "workspace", required = false) String workspaceName,
                                 @PathVariable( name = "layerGroup" ) String layerGroupName) {
        if(workspaceName != null && catalog.getWorkspaceByName(workspaceName) == null) {
            throw new ResourceNotFoundException("Workspace " + workspaceName + " not found");
        }
        
        LOGGER.info( "DELETE layer group " + layerGroupName );
        LayerGroupInfo lg = workspaceName == null ? catalog.getLayerGroupByName( layerGroupName ) :
                catalog.getLayerGroupByName(workspaceName, layerGroupName);
        catalog.remove( lg );
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return LayerGroupInfo.class.isAssignableFrom(methodParameter.getParameterType());
    }

    @Override
    public void configurePersister(XStreamPersister persister, XStreamMessageConverter converter) {
        persister.setCallback(new XStreamPersister.Callback() {
            @Override
            protected Class<LayerGroupInfo> getObjectClass() {
                return LayerGroupInfo.class;
            }

            @Override
            protected CatalogInfo getCatalogObject() {
                Map<String, String> uriTemplateVars = (Map<String, String>) RequestContextHolder.getRequestAttributes().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                String workspace = uriTemplateVars.get("workspace");
                String layerGroup = uriTemplateVars.get("layerGroup");

                if (layerGroup == null) {
                    return null;
                }
                return catalog.getLayerGroupByName(workspace, layerGroup);
            }
            
            @Override
            protected void postEncodeReference(Object obj, String ref, String prefix,
                    HierarchicalStreamWriter writer, MarshallingContext context) {
                if ( obj instanceof StyleInfo ) {
                    StringBuffer link = new StringBuffer();
                    if (prefix != null) {
                        link.append("/workspaces/").append(converter.encode(prefix));
                    }
                    link.append("/styles/").append(converter.encode(ref));
                    converter.encodeLink(link.toString(), writer);
                }
                if ( obj instanceof LayerInfo ) {
                    converter.encodeLink("/layers/" + converter.encode(ref), writer);
                }
            }
        });
    }
}