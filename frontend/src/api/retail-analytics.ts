import { api } from './client';
import { unwrap } from './retail';
import type { components } from './schema';

// The retail analytics reports (issue #149), typed by the generated schema. Read only. Cost, profit
// and margin fields are absent from a body for a caller without retail.profit.read.

type S = components['schemas'];

export type SalesAnalysis = S['RetailSalesAnalysis'];
export type AnalysisRow = S['RetailSalesAnalysisRow'];
export type StockedItem = S['RetailStockedItem'];

export interface SalesAnalysisQuery {
  /** One branch; absent means every branch the caller may read. */
  branchId?: string;
  from: string;
  to: string;
  group: 'day' | 'week' | 'month';
  top: number;
  slowDays: number;
}

export const retailAnalytics = {
  async salesAnalysis({ branchId, from, to, group, top, slowDays }: SalesAnalysisQuery): Promise<SalesAnalysis> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/sales-analysis', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, group, top, slow_days: slowDays } },
      }),
    );
  },
};
