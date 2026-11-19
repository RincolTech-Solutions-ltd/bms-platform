// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AddressSetup, HostnameCheck } from '../../api/onboarding';

// The business's address after Activate (issue #155, ADR-131), in a browser with the API replaced: the operator reads in
// plain words whether the DNS record and the tunnel route were set up, can press "Set up the address again", and then
// reads the hostname check of the address. Every host is invented.

const checkHostname = vi.fn<(slug: string) => Promise<HostnameCheck>>();
const setUpAddressAgain = vi.fn<(id: string) => Promise<AddressSetup>>();

vi.mock('../../api/onboarding', async (original) => {
  const real = await original<typeof import('../../api/onboarding')>();
  return {
    ...real,
    checkHostname: (slug: string) => checkHostname(slug),
    setUpAddressAgain: (id: string) => setUpAddressAgain(id),
  };
});

const { AddressSetupPanel } = await import('./application');

const HOST = 'test-shop-155-bms-staging.example.test';
const FAILED: AddressSetup = {
  host: HOST,
  outcome: 'failed',
  dns_record: 'not_done',
  tunnel_route: 'not_done',
  message: `The address ${HOST} was not set up. Cloudflare had a problem (HTTP 502). Try again in a few minutes. The business is active either way.`,
};
const DONE: AddressSetup = {
  host: HOST,
  outcome: 'set_up',
  dns_record: 'already_there',
  tunnel_route: 'added',
  message: `The address ${HOST} is set up: the DNS record was already there and the tunnel route was added.`,
};

afterEach(() => {
  cleanup();
  checkHostname.mockReset();
  setUpAddressAgain.mockReset();
});

describe('the address set up at Activate (#155)', () => {
  it('says the address was not set up, then runs the hostname check', async () => {
    checkHostname.mockResolvedValueOnce({ host: HOST, outcome: 'not_found' });
    render(<AddressSetupPanel applicationId="app-1" slug="test-shop-155" initial={FAILED} />);

    const said = screen.getByTestId('address-setup');
    expect(said.textContent).toBe(FAILED.message);
    expect(said.className).toContain('alert-warning');
    const status = await screen.findByRole('status');
    expect(checkHostname).toHaveBeenCalledWith('test-shop-155');
    expect(status.textContent).toContain(`${HOST} has no DNS record yet`);
  });

  it('sets the address up again on the button, then checks it again', async () => {
    checkHostname.mockResolvedValueOnce({ host: HOST, outcome: 'not_found' });
    checkHostname.mockResolvedValueOnce({ host: HOST, outcome: 'answers' });
    setUpAddressAgain.mockResolvedValueOnce(DONE);
    render(<AddressSetupPanel applicationId="app-1" slug="test-shop-155" initial={FAILED} />);
    await screen.findByRole('status');

    fireEvent.click(screen.getByRole('button', { name: 'Set up the address again' }));

    expect(await screen.findByText(DONE.message as string)).toBeTruthy();
    expect(setUpAddressAgain).toHaveBeenCalledWith('app-1');
    expect(screen.getByTestId('address-setup').className).toContain('alert-success');
    expect(await screen.findByText(`${HOST} answers. The address is ready for this business.`)).toBeTruthy();
    expect(checkHostname).toHaveBeenCalledTimes(2);
  });

  it('after a reload offers the button without an old result and checks nothing until pressed', () => {
    render(<AddressSetupPanel applicationId="app-1" slug="test-shop-155" />);

    expect(screen.queryByTestId('address-setup')).toBeNull();
    expect(screen.getByRole('button', { name: 'Set up the address again' })).toBeTruthy();
    expect(checkHostname).not.toHaveBeenCalled();
  });

  it('says so in plain words when the retry itself cannot be sent', async () => {
    const { OnboardingError } = await import('../../api/onboarding');
    setUpAddressAgain.mockRejectedValueOnce(new OnboardingError('Address setup is off', 'address_setup_off'));
    render(<AddressSetupPanel applicationId="app-1" slug="test-shop-155" />);

    fireEvent.click(screen.getByRole('button', { name: 'Set up the address again' }));

    expect((await screen.findByRole('alert')).textContent).toBe('Address setup is off');
  });
});
