import { mockMe } from '../api/retail-mock';
import { AddManyForm } from '../areas/staff/retail/add-many';
import { RETAIL_ADD_MANY_TOURS } from './tours-retail-add-many';
import { describeTours } from './test-kit';

// The page tour of Add many items (issue #870): the form is rendered as the user the tour is for, and each step's data-tour
// anchor must be in its markup.

const ADMIN = mockMe('admin');

describeTours('add many items', RETAIL_ADD_MANY_TOURS, { 'page-add-many': [ADMIN] }, () => <AddManyForm />);
