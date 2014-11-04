package it.cnr.jconon.rest;

import it.cnr.cool.cmis.service.CMISService;
import it.cnr.cool.security.SecurityChecked;
import it.cnr.jconon.model.HelpdeskBean;
import it.cnr.jconon.service.helpdesk.HelpdeskService;
import org.apache.commons.beanutils.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;


/**
 * Created by cirone on 27/10/2014.
 */
@Path("helpdesk")
@Component
@Produces(MediaType.APPLICATION_JSON)
@SecurityChecked
public class Helpdesk {
    @Autowired
    private HelpdeskService helpdeskService;
    @Autowired
    private CMISService cmisService;
    @Autowired
    private CommonsMultipartResolver resolver;


    @POST
    @Path("/send")
    public Map<String, Object> uploadFile(@Context HttpServletRequest req) {

        Map<String, Object> model = new HashMap<String, Object>();
        MultipartHttpServletRequest mRequest = resolver.resolveMultipart(req);

        HelpdeskBean hdBean = new HelpdeskBean();
        hdBean.setIp(req.getRemoteAddr());

        try {
            BeanUtils.populate(hdBean, mRequest.getParameterMap());
            String id = mRequest.getParameter("id");
            String azione = mRequest.getParameter("azione");

            if (id != null && azione != null) {
                model = helpdeskService.postReopen(id, azione, hdBean);
            } else {
                model = helpdeskService.post(/*mRequest.getParameterMap(),*/hdBean, mRequest
                        .getFileMap().get("allegato"), cmisService
                        .getCMISUserFromSession(req.getSession()));
            }
        } catch (Exception e) {
            model.put("sendOk", "false");
            model.put("message_error", e.getMessage());
        }
        return model;
    }
}
