package com.bba.model.pv;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class PVSourceDataCollection {
    private String unitId;
    private Map<String, PVSourceData> dataByMonth = new HashMap<>();

    public PVSourceDataCollection(String unitId) {
        this.unitId = unitId;
    }

    public void addData(PVSourceData pvData) {
        if (!pvData.getUnitId().equals(this.unitId)) {
            throw new IllegalArgumentException("Unit ID mismatch: " + this.unitId + " vs " + pvData.getUnitId());
        }
        this.dataByMonth.put(pvData.getValuationMonth(), pvData);
    }

    public PVSourceData getData(String valuationMonth) {
        return this.dataByMonth.get(valuationMonth);
    }
}
