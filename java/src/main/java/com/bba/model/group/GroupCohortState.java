package com.bba.model.group;

import com.bba.model.CohortState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GroupCohortState extends CohortState {
    private String groupId;
    private String portfolioId;
    private List<GroupPolicyState> groupPolicies = new ArrayList<>();
}
