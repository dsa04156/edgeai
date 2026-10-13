package dmi

import (
	"context"
	"fmt"
	"net"
	"time"

	api "github.com/kubeedge/api/apis/dmi/v1beta1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type Client struct {
	Socket, MapperSocket, Protocol, Name, BudgetPath string
	BudgetGap                                        time.Duration
}

func (c Client) call(f func(context.Context, api.DeviceManagerServiceClient) error) error {
	return c.callContext(context.Background(), f)
}
func (c Client) callContext(parent context.Context, f func(context.Context, api.DeviceManagerServiceClient) error) error {
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	dialCtx, dialCancel := context.WithTimeout(ctx, 3*time.Second)
	defer dialCancel()
	conn, err := grpc.DialContext(dialCtx, "passthrough:///dmi", grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithBlock(),
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", c.Socket)
		}))
	if err != nil {
		return &SocketError{Cause: err}
	}
	defer conn.Close()
	return f(ctx, api.NewDeviceManagerServiceClient(conn))
}

func (c Client) Register() (devices []*api.Device, models []*api.DeviceModel, err error) {
	err = c.call(func(ctx context.Context, s api.DeviceManagerServiceClient) error {
		if e := waitBudget(ctx, c.BudgetPath, c.BudgetGap); e != nil {
			return e
		}
		r, e := s.MapperRegister(ctx, &api.MapperRegisterRequest{WithData: true, Mapper: &api.MapperInfo{Name: c.Name, Version: "1.0.0", ApiVersion: "v1.0.0", Protocol: c.Protocol, Address: []byte(c.MapperSocket), State: "ok"}})
		if e == nil {
			devices, models = r.DeviceList, r.ModelList
		}
		return e
	})
	return
}

func (c Client) Report(namespace, name, state string, twins []*api.Twin) error {
	return c.ReportContext(context.Background(), namespace, name, state, twins)
}
func (c Client) ReportContext(parent context.Context, namespace, name, state string, twins []*api.Twin) error {
	return c.ReportLatestContext(parent, namespace, name, state, twins, nil)
}

// ReportLatestContext reads each latest Twin after acquiring its shared DMI slot.
// A nil result suppresses a queued sample when collection is now offline.
func (c Client) ReportLatestContext(parent context.Context, namespace, name, state string, twins []*api.Twin, latest func(string) *api.Twin) error {
	if err := c.callContext(parent, func(ctx context.Context, s api.DeviceManagerServiceClient) error {
		if e := waitBudget(ctx, c.BudgetPath, c.BudgetGap); e != nil {
			return e
		}
		if _, err := s.ReportDeviceStates(ctx, &api.ReportDeviceStatesRequest{DeviceName: name, DeviceNamespace: namespace, State: state}); err != nil {
			return fmt.Errorf("DMI state report: %w", err)
		}
		return nil
	}); err != nil {
		return err
	}
	// EdgeCore 1.23 fans a batch out into simultaneous per-property cloud
	// updates. DeviceController merges whole Twin arrays from its informer
	// cache, so concurrent patches can overwrite each other's property values.
	// Send one property per budgeted RPC. Each RPC keeps a bounded timeout;
	// collection and the latest-only report queue remain independent.
	for _, twin := range twins {
		if err := c.callContext(parent, func(ctx context.Context, s api.DeviceManagerServiceClient) error {
			if e := waitBudget(ctx, c.BudgetPath, c.BudgetGap); e != nil {
				return e
			}
			if latest != nil {
				twin = latest(twin.PropertyName)
				if twin == nil {
					return nil
				}
			}
			_, err := s.ReportDeviceStatus(ctx, &api.ReportDeviceStatusRequest{DeviceName: name, DeviceNamespace: namespace, ReportedDevice: &api.DeviceStatus{Twins: []*api.Twin{twin}}})
			if err != nil {
				return fmt.Errorf("DMI twin report: %w", err)
			}
			return nil
		}); err != nil {
			return err
		}
	}
	return nil
}
