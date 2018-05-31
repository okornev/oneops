package com.oneops.capacity;

import com.google.gson.Gson;
import com.oneops.cms.cm.domain.CmsCI;
import com.oneops.cms.cm.domain.CmsCIAttribute;
import com.oneops.cms.cm.domain.CmsCIRelation;
import com.oneops.cms.cm.service.CmsCmProcessor;
import com.oneops.cms.dj.domain.*;
import com.oneops.cms.dj.service.CmsRfcProcessor;
import com.oneops.cms.util.domain.AttrQueryCondition;
import com.oneops.cms.util.domain.CmsVar;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.*;

public class CapacityProcessor {
    private static Logger logger = Logger.getLogger(CapacityProcessor.class);

    static final String CAPACITY_MANAGEMENT_VAR_NAME = "CAPACITY_MANAGEMENT";
    static final String PROVIDER_MAPPINGS_CMS_VAR_NAME = "CLOUD_PROVIDER_MAPPINGS";

    private CmsCmProcessor cmProcessor;
    private CmsRfcProcessor rfcProcessor;
    private TektonClient tektonClient;

    private Gson gson = new Gson();

    public void setCmProcessor(CmsCmProcessor cmProcessor) {
        this.cmProcessor = cmProcessor;
    }

    public void setRfcProcessor(CmsRfcProcessor rfcProcessor) {
        this.rfcProcessor = rfcProcessor;
    }

    public void setTektonClient(TektonClient tektonClient) {
        this.tektonClient = tektonClient;
    }

    public boolean isCapacityManagementEnabled(String nsPath) {
        CmsVar softQuotaEnabled = cmProcessor.getCmSimpleVar(CAPACITY_MANAGEMENT_VAR_NAME);
        if (softQuotaEnabled != null) {
            String value = softQuotaEnabled.getValue();
            if (Boolean.TRUE.toString().equalsIgnoreCase(value)) return true;
            if (nsPath != null && !value.isEmpty()) {
                return Arrays.stream(value.split(",")).anyMatch(nsPath::startsWith);
            }
        }
        return false;
    }

    public CapacityEstimate estimateCapacity(String nsPath, Collection<CmsRfcCI> cis, Collection<CmsRfcRelation> deployedToRels) {
        if (!isCapacityManagementEnabled(nsPath)) return null;

        Map<String, Object> mappings = getCloudProviderMappings();
        if (mappings == null) return null;

        Map<String, List<CmsRfcCI>> groupedCis = cis.stream().collect(groupingBy(CmsRfcCI::getRfcAction));

        // Capacity to reserve.
        Map<Long, CloudInfo> deployedToCloudInfoMap = getDeployedToCloudInfoMap(deployedToRels);
        Map<String, Map<String, Integer>> increase = getCapacityForCis(groupedCis.get("add"), deployedToRels, deployedToCloudInfoMap, mappings);
        String check = "ok";
        if (increase != null && !increase.isEmpty()) {
            Map<String, String> info = tektonClient.precheckReservation(increase, getReservationNsPath(nsPath), "oneops-system");
            if (info == null) {
                check = "failed";
            } else if (info.isEmpty()) {
                check = "ok";
            } else {
                check = capacityShortageMessage(info, deployedToCloudInfoMap, "");
            }
        }

        // Capacity to release.
        Map<String, Map<String, Integer>> decrease = new HashMap<>();
        List<CmsRfcCI> deleteRfcs = groupedCis.get("delete");
        if (deleteRfcs != null) {
            List<Long> deleteCiIds = deleteRfcs.stream().map(CmsRfcCI::getCiId).collect(toList());
            List<CmsRfcCI> deleteCis = cmProcessor.getCiByIdList(deleteCiIds).stream()
                    .map(ci -> {
                        CmsRfcCI rfc = new CmsRfcCI(ci, null);
                        rfc.setAttributes(ci.getAttributes().values().stream()
                                                  .map(ciAttr -> {
                                                      CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
                                                      rfcAttr.setAttributeId(ciAttr.getAttributeId());
                                                      rfcAttr.setAttributeName(ciAttr.getAttributeName());
                                                      rfcAttr.setNewValue(ciAttr.getDfValue());
                                                      return rfcAttr;
                                                  })
                                                  .collect(toMap(CmsRfcAttribute::getAttributeName, Function.identity())));
                        return rfc;
                    })
                    .collect(toList());
            deployedToRels = cmProcessor.getCIRelationsByFromCiIdsNakedNoAttrs("base.DeployedTo", null, deleteCiIds).stream()
                    .map(rel -> new CmsRfcRelation(rel, null))
                    .collect(toList());
            deployedToCloudInfoMap = getDeployedToCloudInfoMap(deployedToRels);
            decrease = getCapacityForCis(deleteCis, deployedToRels, deployedToCloudInfoMap, mappings);
        }

        return new CapacityEstimate(increase, decrease, check);
    }

    public void reserveCapacityForDeployment(CmsDeployment deployment) {
        String nsPath = deployment.getNsPath();
        if (!isCapacityManagementEnabled(nsPath)) return;

        // Just in case dump any reservation "leftovers".
        deleteReservations(getReservationNsPath(nsPath));

        Map<String, Object> mappings = getCloudProviderMappings();
        if (mappings == null) return;

        // Get capacity increase
        long releaseId = deployment.getReleaseId();
        List<CmsRfcCI> cis = rfcProcessor.getRfcCIBy3(releaseId, true, null);
        List<CmsRfcRelation> deployedToRels = rfcProcessor.getRfcRelationByReleaseAndClassNoAttrs(releaseId, null, "DeployedTo");

        Map<Long, CloudInfo> deployedToCloudInfoMap = getDeployedToCloudInfoMap(deployedToRels);
        List<CmsRfcCI> addCIs = cis.stream().filter(ci -> ci.getRfcAction().equals("add")).collect(toList());
        Map<String, Map<String, Integer>> capacity = getCapacityForCis(addCIs, deployedToRels, deployedToCloudInfoMap, mappings);

        if (!capacity.isEmpty()) {
            try {
                tektonClient.reserveQuota(capacity, getReservationNsPath(nsPath), deployment.getCreatedBy());
            } catch (ReservationException exception) {
                Map<String, String> info = exception.getInfo();
                String message = info == null ? exception.getMessage() : capacityShortageMessage(info, deployedToCloudInfoMap, "Failed to reserve capacity:\n");
                throw new RuntimeException(message);
            }
        }
    }

    public void discardCapacityForDeployment(CmsDeployment deployment) {
        String nsPath = deployment.getNsPath();
        if (!isCapacityManagementEnabled(nsPath)) return;

        deleteReservations(getReservationNsPath(nsPath));
    }

    public void commitCapacity(CmsWorkOrder workOrder) {
        CmsRfcCI rfcCi = workOrder.getRfcCi();
        String nsPath = getReservationNsPath(rfcCi.getNsPath());
        if (!isCapacityManagementEnabled(nsPath)) return;

        Map<String, Object> mappings = getCloudProviderMappings();
        if (mappings == null) return;

        List<CmsRfcRelation> deployedTos = rfcProcessor.getOpenRfcRelationBy2NoAttrs(rfcCi.getCiId(), null, "base.DeployedTo", null);
        CloudInfo cloudInfo = getDeployedToCloudInfo(deployedTos.get(0).getToCiId());
        Map<String, Integer> capacity = getCapacityForCi(rfcCi, cloudInfo, mappings);

        if (!capacity.isEmpty()) {
            tektonClient.commitReservation(capacity, nsPath, cloudInfo.getSubscriptionId());
        }
    }

    public void releaseCapacity(CmsWorkOrder workOrder) {
        CmsRfcCI rfcCi = workOrder.getRfcCi();
        String nsPath = getReservationNsPath(rfcCi.getNsPath());
        if (!isCapacityManagementEnabled(nsPath)) return;

        Map<String, Object> mappings = getCloudProviderMappings();
        if (mappings == null) return;

        List<CmsCIRelation> deployedTos = cmProcessor.getFromCIRelationsNakedNoAttrs(rfcCi.getCiId(), "base.DeployedTo", null, null);
        CloudInfo cloudInfo = getDeployedToCloudInfo(deployedTos.get(0).getToCiId());
        Map<String, Integer> capacity = getCapacityForCi(rfcCi, cloudInfo, mappings);

        if (!capacity.isEmpty()) {
            tektonClient.releaseResources(capacity, nsPath, cloudInfo.getSubscriptionId());
        }
    }

    private String getReservationNsPath(String fullNsPath) {
        String[] tokens = fullNsPath.split("/");
        return "/" + tokens[1] + "/" + tokens[2] + "/" + tokens[3];
    }

    private Map<String, Object> getCloudProviderMappings() {
        Map<String, Object> mappings = null;
        CmsVar cmsVar = cmProcessor.getCmSimpleVar(PROVIDER_MAPPINGS_CMS_VAR_NAME);
        if (cmsVar != null) {
            String json = cmsVar.getValue();
            if (json != null && !json.isEmpty()) {
                mappings = gson.fromJson(cmsVar.getValue(), Map.class);
            }
        }

        if (mappings == null || mappings.size() == 0) {
            logger.warn("Cloud provider mappings is not set.");
            mappings = null;
        }

        return mappings;
    }

    private Map<Long, CloudInfo> getDeployedToCloudInfoMap(Collection<CmsRfcRelation> deployedToRels) {
        return deployedToRels.stream()
                .map(CmsRfcRelation::getToCiId)
                .distinct()
                .collect(toMap(Function.identity(), this::getDeployedToCloudInfo));
    }

    private CloudInfo getDeployedToCloudInfo(long cloudCiId) {
        CmsCI cloud = cmProcessor.getCiById(cloudCiId);
        return getDeployedToCloudInfo(cloud);
    }

    private CloudInfo getDeployedToCloudInfo(CmsCI cloud) {
        long cloudCiId = cloud.getCiId();
        List<AttrQueryCondition> attrConds = new ArrayList<>();
        attrConds.add(new AttrQueryCondition("service", "eq", "compute"));
        List<CmsCIRelation> providesRels = cmProcessor.getFromCIRelationsByAttrs(cloudCiId, null, "Provides", null, attrConds);
        if (providesRels.isEmpty()) {
            logger.warn("Could not find compute services for cloudId: " + cloudCiId);
            return null;
        }
        CmsCI computeService = providesRels.get(0).getToCi();

        String[] classNameTokens = computeService.getCiClassName().split("\\.");
        String provider = classNameTokens[classNameTokens.length - 1].toLowerCase();

        String location = null;
        CmsCIAttribute attr = cloud.getAttribute("location");
        if (attr != null) {
            String[] tokens = attr.getDfValue().split("/");
            location = tokens[tokens.length - 1];
        }
        if (location == null) return null;

        String subscriptionId;
        attr = computeService.getAttribute("subscription");
        if (attr == null) {
            attr = computeService.getAttribute("tenant");
        }
        if (attr == null) {
            logger.warn("Neither subscription nor tenant attributes are present for compute cloud service: " + computeService.getCiName() + ", cloudID: " + cloudCiId);
            return null;
        } else {
            String subscription = attr.getDfValue();
            subscriptionId = location + ":" + subscription;
        }

        return new CloudInfo(cloudCiId, cloud.getCiName(), provider, subscriptionId);
    }

    private Map<String, Map<String, Integer>> getCapacityForCis(Collection<CmsRfcCI> cis,
                                                                Collection<CmsRfcRelation> deployedToRels,
                                                                Map<Long, CloudInfo> cloudInfoMap,
                                                                Map<String, Object> mappings) {
        Map<String, Map<String, Integer>> reservation = new HashMap<>();

        if (cis == null) return reservation;

        Map<Long, Long> ciToCloudMap = deployedToRels.stream()
                .collect(toMap(CmsRfcRelation::getFromCiId, CmsRfcRelation::getToCiId));

        for (CmsRfcCI rfcCI : cis) {
            CloudInfo cloudInfo = cloudInfoMap.get(ciToCloudMap.get(rfcCI.getCiId()));
            Map<String, Integer> ciCapacity = getCapacityForCi(rfcCI, cloudInfo, mappings);
            if (!ciCapacity.isEmpty()) {
                Map<String, Integer> subCapacity = reservation.computeIfAbsent(cloudInfo.getSubscriptionId(), (k) -> new HashMap<>());
                for (String resource : ciCapacity.keySet()) {
                    subCapacity.put(resource, subCapacity.computeIfAbsent(resource, (k) -> 0) + ciCapacity.get(resource));
                }
            }
        }

        return reservation;
    }

    private Map<String, Integer> getCapacityForCi(CmsRfcCI rfcCi, CloudInfo cloudInfo, Map<String, Object> mappings) {
        Map<String, Integer> capacity = new HashMap<>();

        if (cloudInfo == null) {
            logger.warn("Could not determine cloud provider/subscription for rfc rfcId: " + rfcCi.getRfcId());
            return capacity;
        }

        Map<String, Object> providerMappings = (Map<String, Object>) mappings.get(cloudInfo.getProvider());
        if (providerMappings == null) return capacity;

        String[] classNameSplit = rfcCi.getCiClassName().split("\\.");
        Map<String, Object> classMappings = (Map<String, Object>) providerMappings.get(classNameSplit[classNameSplit.length - 1].toLowerCase());
        if (classMappings == null) return capacity;

        for (String attrName : classMappings.keySet()) {
            Map<String, Object> attrMappings = (Map<String, Object>) classMappings.get(attrName);
            Map<String, Object> resources = null;

            // First try to map resources based on attribute value.
            CmsRfcAttribute attr = rfcCi.getAttribute(attrName);
            String value = (attr == null ? null : attr.getNewValue());
            if (value != null && !value.isEmpty()) {
                resources = (Map<String, Object>) attrMappings.get(value);
            }

            // If attribute is missing or value is not mapped, try to map to "otherwise" resources if they are specified via "*" key.
            if (resources == null) {
                // Try to go by "else" value if it is specified via "*" key.
                resources = (Map<String, Object>) attrMappings.get("*");
            }

            if (resources == null) continue;
            for (String resource : resources.keySet()) {
                capacity.put(resource, capacity.computeIfAbsent(resource, (k) -> 0) + ((Double) resources.get(resource)).intValue());
            }
        }

        return capacity;
    }

    private void deleteReservations(String nsPath) {
        Set<String> subs = cmProcessor.getCountCIRelationsGroupByToCiId("base.Consumes", null, null, nsPath)
                .keySet()
                .stream()
                .map(this::getDeployedToCloudInfo)
                .filter(Objects::nonNull)
                .map(CloudInfo::getSubscriptionId)
                .collect(toSet());
        if (!subs.isEmpty()) {
            tektonClient.deleteReservations(nsPath, subs);
        }
    }

    private String capacityShortageMessage(Map<String, String> info, Map<Long, CloudInfo> cloudInfo, String prefix) {
        String check;
        Map<String, List<String>> cloudsBySubscription = new HashMap<>();
        cloudInfo.values().forEach(i -> cloudsBySubscription.computeIfAbsent(i.getSubscriptionId(), s -> new ArrayList<>()).add(i.getCiName()));
        check = info.entrySet().stream()
                .map(i -> String.join(", ", cloudsBySubscription.get(i.getKey())) + " - " + i.getValue())
                .collect(joining(";\n", prefix, ""));
        return check;
    }


    private class CloudInfo {
        private long ciId;
        private String ciName;
        private String provider;
        private String subscriptionId;

        CloudInfo(long ciId, String ciName, String provider, String subscriptionId) {
            this.ciId = ciId;
            this.ciName = ciName;
            this.provider = provider;
            this.subscriptionId = subscriptionId;
        }

        long getCiId() {
            return ciId;
        }

        String getProvider() {
            return provider;
        }

        String getSubscriptionId() {
            return subscriptionId;
        }

        public String getCiName() {
            return ciName;
        }
    }
}
