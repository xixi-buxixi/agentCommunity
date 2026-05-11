package com.pulse.service;

import com.pulse.entity.FrontierNewsSource;
import com.pulse.service.impl.FrontierNewsServiceImpl.PublishStats;

import java.util.List;

public interface FrontierNewsService {

    PublishStats publishFetchedItems(List<FrontierNewsSource> items);
}
