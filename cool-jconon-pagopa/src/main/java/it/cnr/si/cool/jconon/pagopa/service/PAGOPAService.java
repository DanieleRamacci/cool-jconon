/*
 * Copyright (C) 2023 Consiglio Nazionale delle Ricerche
 *       This program is free software: you can redistribute it and/or modify
 *        it under the terms of the GNU Affero General Public License as
 *        published by the Free Software Foundation, either version 3 of the
 *        License, or (at your option) any later version.
 *
 *        This program is distributed in the hope that it will be useful,
 *        but WITHOUT ANY WARRANTY; without even the implied warranty of
 *        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *       GNU Affero General Public License for more details.
 *
 *       You should have received a copy of the GNU Affero General Public License
 *       along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package it.cnr.si.cool.jconon.pagopa.service;

import feign.FeignException;
import it.cnr.cool.cmis.model.ACLType;
import it.cnr.cool.cmis.model.CoolPropertyIds;
import it.cnr.cool.cmis.service.ACLService;
import it.cnr.cool.cmis.service.CMISService;
import it.cnr.cool.web.scripts.exception.ClientMessageException;
import it.cnr.si.cool.jconon.pagopa.config.PAGOPAConfigurationProperties;
import it.cnr.si.cool.jconon.pagopa.model.*;
import it.cnr.si.cool.jconon.pagopa.model.pagamento.NotificaPagamento;
import it.cnr.si.cool.jconon.pagopa.model.pagamento.RiferimentoAvviso;
import it.cnr.si.cool.jconon.pagopa.model.pagamento.RiferimentoAvvisoResponse;
import it.cnr.si.cool.jconon.pagopa.repository.Pagopa;
import it.cnr.si.opencmis.criteria.Criteria;
import it.cnr.si.opencmis.criteria.CriteriaFactory;
import it.cnr.si.opencmis.criteria.restrictions.Restrictions;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.StreamSupport;

@Service
public class PAGOPAService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PAGOPAService.class);
    @Autowired
    private ACLService aclService;
    @Autowired
    private CMISService cmisService;

    @Autowired
    private PAGOPAConfigurationProperties properties;
    @Autowired
    private Pagopa pagopa;
    @Autowired
    private Pagopa pagopaDownload;

    public Pendenza creaPendenza(PendenzaDTO pendenzaDTO, Long numProtocollo) {
        final Pendenza pendenza = pendenzaDTO.toPendenza(properties, numProtocollo);
        pagopa.creaPendenza(
                properties.getGovpay().getDominio(),
                numProtocollo,
                Boolean.FALSE,
                pendenza
        );
        return pendenza;
    }

    public byte[] stampaRicevuta(String iuv, String ccp) {
        return pagopaDownload.stampaRt(properties.getCodicefiscale(), iuv, ccp, Boolean.TRUE);
    }

    public byte[] stampaAvviso(String iuv) {
        return pagopaDownload.stampaAvviso(properties.getCodicefiscale(), iuv);
    }

    public void annullaPendenza(Long numProtocollo) {
        pagopa.aggiornaPendenza(
                properties.getGovpay().getDominio(),
                numProtocollo,
                Collections.singletonList(new AnnullaPendenza())
        );
    }

    public RiferimentoAvvisoResponse pagaAvviso(String idPendenza, String url) {
        try {
            final Pendenza pendenza = new Pendenza();
            pendenza.setIdA2A(properties.getGovpay().getDominio());
            pendenza.setIdPendenza(idPendenza);
            RiferimentoAvviso riferimentoAvviso = new RiferimentoAvviso();
            riferimentoAvviso.setPendenze(Collections.singletonList(pendenza));
            riferimentoAvviso.setUrlRitorno(url);
            return pagopa.pagaAvviso(riferimentoAvviso);
        } catch (FeignException.FeignServerException _ex) {
            throw new ClientMessageException(_ex.getMessage());
        }
    }

    public RiferimentoAvvisoResponse getAvviso(String id) {
        return pagopa.getAvviso(id);
    }
    public void notificaPagamento(Session currentCMISSession, String ccp, String iuv) throws IOException {
        Criteria criteriaApplications = CriteriaFactory.createCriteria(PAGOPAObjectType.JCONON_APPLICATION_PAGOPA.queryName());
        criteriaApplications.add(Restrictions.eq(PAGOPAPropertyIds.APPLICATION_NUMERO_AVVISO_PAGOPA.value(),
                properties.getGovpay().getCodicestazione().concat(iuv)));
        final Folder application = Optional.ofNullable(criteriaApplications.executeQuery(
                        currentCMISSession,
                        Boolean.FALSE,
                        currentCMISSession.getDefaultContext()
                ).iterator().next())
                .map(queryResult -> queryResult.<String>getPropertyValueById(PropertyIds.OBJECT_ID))
                .map(s -> currentCMISSession.getObject(s))
                .filter(Folder.class::isInstance)
                .map(Folder.class::cast)
                .orElseThrow(() -> new RuntimeException("Application not found for iuv: " + iuv));
        if (!StreamSupport.stream(application.getChildren().spliterator(), false)
                .filter(cmisObject -> cmisObject.getType().getId().equals(PAGOPAObjectType.JCONON_ATTACHMENT_PAGAMENTI_DIRITTI_SEGRETERIA.value()))
                .findAny().isPresent()) {
            String fileName = "ricevuta_pagamento.pdf";
            final byte[] ricevutaPagamento = stampaRicevuta(iuv, ccp);
            InputStream is = new ByteArrayInputStream(ricevutaPagamento);
            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put(PropertyIds.OBJECT_TYPE_ID, PAGOPAObjectType.JCONON_ATTACHMENT_PAGAMENTI_DIRITTI_SEGRETERIA.value());
            properties.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, Arrays.asList("P:jconon_attachment:generic_document"));
            properties.put(PAGOPAPropertyIds.ATTACHMENT_ESTREMI_PAGAMENTO_DIRITTI_SEGRETERIA.value(), ccp);
            properties.put(PropertyIds.NAME, fileName);
            ContentStream contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(is.available()), "application/pdf", is);
            Document doc = application.createDocument(properties, contentStream, VersioningState.MAJOR);
            aclService.addAcl(
                    cmisService.getAdminSession(),
                    doc.<String>getPropertyValue(CoolPropertyIds.ALFCMIS_NODEREF.value()),
                    Collections.singletonMap(application.getPropertyValue("jconon_application:user"), ACLType.Coordinator)
            );
            if (LOGGER.isInfoEnabled())
                LOGGER.info("É stato correttamente generato la ricevuta di pagamento per la domanda con id: {} documento id: {}", application.getId(), doc.getId());
        }
    }
    public void notificaPagamento(Session currentCMISSession, NotificaPagamento pagamento, String iuv) throws IOException {
        notificaPagamento(currentCMISSession, pagamento.getRt().getReceiptId(), iuv);
    }
}
