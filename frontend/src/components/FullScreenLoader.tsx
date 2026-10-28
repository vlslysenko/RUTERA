import { Center, Loader } from '@mantine/core';

export function FullScreenLoader({ fullScreen = true }: { fullScreen?: boolean }) {
  return (
    <Center mih={fullScreen ? '100vh' : 160}>
      <Loader size="sm" />
    </Center>
  );
}
