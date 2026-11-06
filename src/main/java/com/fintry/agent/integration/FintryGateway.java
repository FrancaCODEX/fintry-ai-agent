package com.fintry.agent.integration;

import com.fintry.agent.integration.FinancialData.*;
import java.time.YearMonth;

public interface FintryGateway {
  UserContext authenticate(String sessionCookie);

  Summary summary(UserContext user, YearMonth month);

  Pending pending(UserContext user);

  Forecast forecast(UserContext user, int days);
}
